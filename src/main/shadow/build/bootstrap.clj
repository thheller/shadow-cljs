(ns shadow.build.bootstrap
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.util :as util]
            [shadow.build.classpath :as cp]
            [shadow.build.resolve :as resolve]
            [shadow.build.compiler :as impl]
            [shadow.build.data :as data]
            [shadow.build.cache :as cache]
            [shadow.build.modules :as modules]
            [clojure.data.json :as json]))

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

(defn append-load-info [state]
  (reduce
    (fn [state {:keys [module-id sources] :as mod}]
      (let [all-provides
            (->> sources
                 (map #(data/get-source-by-id state %))
                 (map :provides)
                 (reduce set/union))

            load-str
            (str "shadow.bootstrap.set_loaded(" (json/write-str all-provides) ");")]

        (update-in state [:output [::modules/append module-id] :js] str "\n" load-str "\n")
        ))
    state
    (:build-modules state)))

(defn compile [{:keys [bootstrap-options build-sources] :as state}]
  (util/with-logged-time [state {:type ::compile}]
    (let [{:keys [entries macros]} bootstrap-options

          entries
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

          #_ #_ macros-from-deps
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

      (-> state
          (assoc ::deps deps)
          (assoc :build-sources deps)
          (impl/compile-all)
          (assoc :build-sources build-sources)
          (assoc-in [:compiler-options :closure-defines 'shadow.bootstrap/asset-path]
            (str (get-in state [:build-options :asset-path]) "/bootstrap"))
          (append-load-info))
      )))

(defn make-index [{::keys [deps] :as state}]
  (->> deps
       (map #(data/get-source-by-id state %))
       (map (fn [{:keys [type requires provides ns deps resource-id resource-name output-name] :as rc}]
              (let [resolved-deps (data/deps->syms state rc)]
                (-> {:resource-id resource-id
                     :type type
                     :provides provides
                     :requires (into #{} resolved-deps)
                     :ns ns
                     :deps resolved-deps
                     :source-name (util/flat-filename resource-name)
                     :output-name output-name}
                    (cond->
                      (= :cljs type)
                      (assoc :macro-requires (:macro-requires rc))
                      )))
              ))
       (into [])))

(defn flush [{::keys [deps] :keys [bootstrap-options] :as state}]
  (util/with-logged-time [state {:type ::flush}]
    (let [bootstrap-dir
          (data/output-file state "bootstrap")

          index-file
          (io/file bootstrap-dir "index.transit.json")

          index
          (make-index state)]

      (io/make-parents index-file)
      (cache/write-cache index-file index)

      (let [loaded-by-build
            (->> (:build-sources state)
                 (into #{}))

            source-dir
            (io/file bootstrap-dir "src")

            js-dir
            (io/file bootstrap-dir "js")

            ana-dir
            (io/file bootstrap-dir "ana")

            required-js-names
            (data/js-names-accessed-from-cljs state deps)]

        (io/make-parents source-dir "foo")
        (io/make-parents js-dir "foo")
        (io/make-parents ana-dir "foo")

        (doseq [dep-id deps]
          (let [{:keys [type resource-name resource-id output-name ns] :as src}
                (data/get-source-by-id state dep-id)

                {:keys [js] :as output}
                (data/get-output! state src)

                source-name
                (util/flat-filename resource-name)

                source-file
                (io/file source-dir source-name)

                js-file
                (io/file js-dir output-name)]

            (spit source-file (:source src))

            (spit js-file
              (str js
                   (when (contains? required-js-names ns)
                     ;; goog.provide so goog.require is happy
                     (str "\ngoog.provide(\"" ns "\");"
                          "\ngoog.global. " ns "=shadow.js.require(\"" ns "\");\n"))))

            (when (= type :cljs)
              (let [ana
                    (get-in state [:compiler-env :cljs.analyzer/namespaces ns])

                    ana-file
                    (io/file ana-dir (str source-name ".ana.transit.json"))]

                (cache/write-cache ana-file ana)
                ))

            ))))

    state))
