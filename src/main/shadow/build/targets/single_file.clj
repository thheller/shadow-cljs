(ns shadow.build.targets.single-file
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
  [state mode {:keys [build-id output-to entry entries] :as config}]
  (let [runtime
        (api/get-runtime!)

        asset-path
        (str "http://localhost:9630/cache/" (name build-id) "/dev/out")

        output-to
        (io/file output-to)

        output-dir
        (if (= :dev mode)
          (io/file (get-in runtime [:config :cache-root]) "builds" (name build-id) "dev" "out")
          (.getParentFile output-to))

        module-config
        {:entries (or entries [entry])
         :output-name (.getName output-to)}]

    (-> state
        (assoc ::output-to (io/file output-to))
        (build-api/merge-build-options
          {:asset-path asset-path
           :output-dir output-dir})

        (build-api/with-js-options
          {:js-provider :shadow})

        (modules/configure {:main module-config})
        )))

(defn generate-source-maps
  [state]
  (reduce
    (fn [state resource-id]
      (let [rc
            (data/get-source-by-id state resource-id)

            {:keys [js source-map-info source-map source-map-json] :as output}
            (data/get-output! state rc)]
        (if (some? source-map-info)
          state
          (let [sm
                (or (and source-map (output/encode-source-map state rc output))
                    (and (seq source-map-json) (json/read-str source-map-json))
                    {})

                lines
                (output/line-count js)]

            (assoc-in state [:output resource-id :source-map-info]
              {:lines lines
               :source-map sm})
            ))))
    state
    (-> state :build-modules first :sources)))

(defn create-index-map [state header sources output-to]
  (let [prepend-offset
        (output/line-count header)

        sm-file
        (io/file (.getParentFile output-to) (str (.getName output-to) ".map"))

        sm-index
        (-> {:version 3
             :file (.getName output-to)
             :offset prepend-offset
             :sections []}
            (util/reduce->
              (fn [{:keys [offset] :as sm-index} resource-id]

                (let [rc
                      (data/get-source-by-id state resource-id)

                      {:keys [lines source-map]}
                      (get-in state [:output resource-id :source-map-info])]

                  (-> sm-index
                      (update :offset + lines)
                      (cond->
                        (seq source-map)
                        (update :sections conj {:offset {:line offset :column 0}
                                                :map source-map})))))

              sources)
            (dissoc :offset))]

    (spit sm-file (json/write-str sm-index))))

(defn flush-dev
  [{::keys [output-to] :as state}]

  (io/make-parents output-to)

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
             append

             (str "\n//# sourceMappingURL=" (.getName output-to) ".map\n"))]

    (create-index-map state header sources output-to)
    (spit output-to out))

  state)

(defn process
  [{::b/keys [stage mode config] :as state}]
  state
  (cond
    (= :configure stage)
    (configure state mode config)

    (and (= :dev mode) (= :flush stage))
    (-> state
        (generate-source-maps)
        (flush-dev))

    (and (= :release mode) (= :flush stage))
    (output/flush-optimized state)

    :else
    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :chrome-bg)
  (shadow.cljs.devtools.api/compile :chrome-content))

