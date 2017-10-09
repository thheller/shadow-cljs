(ns shadow.build.targets.bootstrap
  (:refer-clojure :exclude (compile flush resolve))
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.util :as util]
            [shadow.build :as build]
            [shadow.build.classpath :as cp]
            [shadow.build.resolve :as resolve]
            [shadow.build.compiler :as impl]
            [shadow.build.data :as data]
            [shadow.build.cache :as cache]
            [shadow.build.modules :as modules]
            [clojure.data.json :as json]
            [shadow.build.api :as build-api]
            [cljs.compiler :as comp]
            [clojure.string :as str]))

(defn make-macro-resource [macro-ns]
  (let [path
        (util/ns->path macro-ns)

        rc-url
        (or (io/resource (str path ".clj"))
            (io/resource (str path ".cljc")))

        last-mod
        (-> rc-url
            (.openConnection)
            (.getLastModified))

        rc
        (->> {:resource-id [::macro macro-ns]
              :resource-name (str path "$macros.cljc")
              :type :cljs
              :url rc-url
              :last-modified last-mod
              :cache-key last-mod
              :macros-ns true
              :output-name (str macro-ns "$macros.js")
              :source (slurp rc-url)}
             ;; extract requires, deps
             (cp/inspect-cljs {}))]

    rc
    ))


(defn resolve [state {:keys [entries macros] :as config}]
  (util/with-logged-time [state {:type ::compile}]
    (let [entries
          (into '[cljs.core] entries)

          [deps state]
          (resolve/resolve-entries state entries)

          ;; resolving macros in a second pass
          ;; because they are circular in nature
          ;; cljs.core requires cljs.core$macros which requires on cljs.core
          ;; the files themselves do not depend on the macros when compiled normally
          ;; only the bootstrap compiler will require them
          macros-from-deps
          (->> (for [dep-id deps
                     :let [{:keys [macro-requires] :as src} (data/get-source-by-id state dep-id)]
                     macro-ns macro-requires]
                 macro-ns)
               (distinct)
               (into []))

          #_#_macros-from-deps
              '[demo.macro]

          macros
          (into macros-from-deps macros)

          macro-resources
          (into [] (map make-macro-resource) macros)

          all-entries
          (-> []
              (into entries)
              (into (map :ns) macro-resources))

          [deps state]
          (-> state
              (util/reduce-> data/add-virtual-resource macro-resources)
              (resolve/resolve-entries all-entries))]

      (assoc state :build-sources deps)
      )))

(defn make-index [output-data]
  (->> output-data
       (map #(dissoc %
               :source-file :source
               :js-file :js
               :ana-file :ana-json))
       (into [])))

(defn prepare-output [state build-sources mode]
  (let [hash-fn
        (if (= :dev mode)
          ;; dev mode just use the name, avoids generating too many hash filenames
          (fn [name text] name)
          ;; release mode names are <hash>.name
          (fn [name text]
            (str (-> (util/md5hex text)
                     ;; no idea how likely conflicts are if only taking the first 4 bytes?
                     (subs 0 8)
                     (str/lower-case))
                 "." name)))]

    (->> build-sources
         (map (fn [src-id]
                (let [{:keys [type source resource-name resource-id output-name ns] :as src}
                      (data/get-source-by-id state src-id)

                      {:keys [js] :as output}
                      (data/get-output! state src)

                      flat-name
                      (hash-fn (util/flat-filename resource-name) source)

                      source-name
                      (str "/src/" flat-name)

                      source-file
                      (data/output-file state "src" flat-name)

                      js
                      (str js
                           ;; always expose all JS names, we don't know which the user is going to use
                           (when (= :npm type)
                             (str "\ngoog.provide(\"" ns "\");"
                                  "\ngoog.global. " ns "=shadow.js.require(\"" ns "\");\n")))

                      js-name-hash
                      (hash-fn output-name js)

                      js-name
                      (str "/js/" js-name-hash)

                      js-file
                      (data/output-file state "js" js-name-hash)

                      [ana-file ana-name ana-json :as ana]
                      (when (= type :cljs)
                        (let [ana
                              (get-in state [:compiler-env :cljs.analyzer/namespaces ns])

                              ana-json
                              (cache/write-str ana)

                              ns-name
                              (hash-fn (comp/munge ns) ana-json)

                              ana-name
                              (str "/ana/" ns-name ".transit.json")

                              ana-file
                              (data/output-file state "ana" (str ns-name ".transit.json"))]

                          [ana-file ana-name ana-json]))

                      resolved-deps
                      (data/deps->syms state src)]

                  (-> {:resource-id resource-id
                       :type type
                       :provides (:provides src)
                       :requires (into #{} resolved-deps)
                       :deps resolved-deps
                       :ns ns
                       :source-name source-name
                       :source source
                       :source-file source-file
                       :js js
                       :js-name js-name
                       :js-file js-file}
                      (cond->
                        (= type :cljs)
                        (assoc :macro-requires (:macro-requires src))
                        ana
                        (assoc
                          :ana-file ana-file
                          :ana-name ana-name
                          :ana-json ana-json
                          )))
                  ))))))

(defn flush [{:keys [build-sources bootstrap-options] :as state} mode]
  (util/with-logged-time [state {:type ::flush}]
    (let [index-file
          (data/output-file state "index.transit.json")

          index-dir
          (.getParentFile index-file)

          output-data
          (prepare-output state build-sources mode)

          index
          (make-index output-data)]

      (io/make-parents index-file)

      (io/make-parents index-dir "src" "foo")
      (io/make-parents index-dir "js" "foo")
      (io/make-parents index-dir "ana" "foo")

      (cache/write-file index-file index)

      (doseq [{:keys [type
                      source-file source
                      js-file js
                      ana-file ana-json] :as x}
              output-data]

        (spit source-file source)
        (spit js-file js)

        (when (= type :cljs)
          (spit ana-file ana-json)
          )))

    state))

(defn configure [state mode {:keys [output-dir] :as config}]
  (-> state
      (assoc ::build/skip-optimize true)
      (build-api/with-build-options
        {:output-dir (io/file output-dir)})
      ;; FIXME: allow closure?
      (assoc-in [:js-options :js-provider] :shadow)))

(defn process
  [{::build/keys [stage mode config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :resolve
    (resolve state config)

    :compile-finish
    state

    :flush
    (flush state mode)

    state
    ))