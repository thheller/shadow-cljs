(ns shadow.build.targets.graaljs
  (:require
    [clojure.java.io :as io]
    [shadow.build :as b]
    [shadow.build.targets.browser :as browser]
    [shadow.build.targets.shared :as shared]
    [shadow.cljs.repl :as repl]
    [shadow.build.api :as build-api]
    [shadow.cljs.devtools.api :as api]
    [shadow.build.modules :as modules]
    [shadow.build.output :as output]
    [shadow.build.data :as data]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [shadow.cljs.util :as util]))

(defn configure
  [state mode {:keys [output-to entries preloads append append-js prepend prepend-js] :as config}]
  (let [output-file
        (io/file output-to)

        output-dir
        (-> output-file
            (.getParentFile)
            (.getAbsolutePath))

        module-config
        {:main
         (-> {:module-id :main
              :default true
              :entries entries
              :output-name (.getName output-file)
              :prepend-js (slurp (io/resource "shadow/build/targets/graaljs_bootstrap.js"))}
             (cond->
               preloads
               (assoc :preloads preloads)
               prepend-js
               (update :prepend-js str "\n" prepend-js)
               prepend
               (assoc :prepend prepend)
               append-js
               (assoc :append-js append-js)
               append
               (assoc :append append)))}]

    ;; FIXME: should this just default to :simple?
    (-> state
        (assoc ::output-file output-file)
        (build-api/merge-build-options
          {:asset-path "/"
           :output-dir output-dir})

        (build-api/with-js-options
          {:js-provider :shadow})

        (cond->
          (nil? (get-in config [:compiler-options :output-feature-set]))
          (assoc-in [:compiler-options :output-feature-set] :es6))

        (modules/configure module-config)
        )))

;; create index source map readable by shadow.cljs.graaljs/source-map
(defn create-index-map [state header sources output-to]
  (let [prepend-offset
        (output/line-count header)

        sm-file
        (io/file (.getParentFile output-to) (str (.getName output-to) ".map"))

        sm-index
        (-> {:version 3
             :file (.getName output-to)
             :offset prepend-offset
             :prependOffset prepend-offset
             :sections []}
            (util/reduce->
              (fn [{:keys [offset] :as sm-index} resource-id]

                (let [{:keys [resource-name] :as rc}
                      (data/get-source-by-id state resource-id)

                      {:keys [js source-map-compact source] :as output}
                      (data/get-output! state rc)

                      lines
                      (inc (count (.split js "\n")))

                      sm
                      (assoc source-map-compact
                        "version" 3
                        "sources" [resource-name]
                        "sourcesContent" [source])]

                  (-> sm-index
                      (update :offset + lines)
                      (cond->
                        (seq source-map-compact)
                        (update :sections conj {:offset {:line offset :column 0}
                                                :map sm}
                          )))))

              sources)
            (dissoc :offset))]

    (spit sm-file (json/write-str sm-index))
    sm-file
    ))


(defn flush-dev
  [{::keys [output-file] :as state}]

  (io/make-parents output-file)

  (let [{:keys [prepend append sources] :as mod}
        (-> state :build-modules first)

        header
        (str prepend
             "var shadow$provide = {};\n"

             (let [{:keys [polyfill-js]} state]
               (when (seq polyfill-js)
                 (str "\n" polyfill-js)))

             (output/closure-defines-and-base state)

             "goog.global[\"$CLJS\"] = goog.global;\n"

             ;; technically don't need this until we get a REPL in there somehow
             (slurp (io/resource "shadow/boot/static.js"))
             "\n\n"

             (let [require-files
                   (->> sources
                        (map #(data/get-source-by-id state %))
                        (map :output-name)
                        (into []))]
               (str "SHADOW_ENV.load({}, " (json/write-str require-files) ");\n"))

             "\n\n")

        out
        (str header
             (->> sources
                  (map #(data/get-source-by-id state %))
                  (map #(data/get-output! state %))
                  (map :js)
                  (str/join "\n"))
             append)]

    (spit output-file out)

    (when (get-in state [:compiler-options :source-map])
      (create-index-map state header sources output-file)))

  state)

(defn process
  [{::b/keys [stage mode config] :as state}]
  state
  (cond
    (= :configure stage)
    (configure state mode config)

    (and (= :dev mode) (= :flush stage))
    (flush-dev state)

    (and (= :release mode) (= :flush stage))
    (output/flush-optimized state)

    :else
    state))