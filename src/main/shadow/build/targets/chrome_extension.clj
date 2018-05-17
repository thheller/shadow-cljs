(ns shadow.build.targets.chrome-extension
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
    [clojure.string :as str]))

(defn configure
  [state mode {:keys [build-id output-to entry] :as config}]
  (let [runtime
        (api/get-runtime!)

        asset-path
        (str "http://localhost:9630/cache/" (name build-id) "/dev/out")

        output-dir
        (io/file (get-in runtime [:config :cache-root]) "builds" (name build-id) "dev" "out")]

    (-> state
        (assoc ::output-to (io/file output-to))
        (build-api/merge-build-options
          {:asset-path asset-path
           :output-dir output-dir})

        (build-api/with-js-options
          {:js-provider :shadow})

        (cond->
          (and (= :dev mode) (:worker-info state))
          (-> (repl/setup)
              (shared/merge-repl-defines config)))

        (modules/configure
          {:main {:entries [entry]}})
        )))

(defn flush-dev
  [{:keys [build-options]
    ::keys [output-to]
    :as state}]

  (io/make-parents output-to)

  (let [{:keys [goog-base output-name prepend append sources web-worker] :as mod}
        (-> state :build-modules first)]

    (let [out
          (str prepend
               "var shadow$provide = {};\n"

               (let [{:keys [polyfill-js]} state]
                 (when (and goog-base (seq polyfill-js))
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
                 (str "SHADOW_ENV.load(" (json/write-str require-files) ");\n"))

               "\n\n"

               (->> sources
                    (map #(data/get-source-by-id state %))
                    (map #(data/get-output! state %))
                    (map :js)
                    (str/join "\n"))

               append)]

      (spit output-to out)))

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
    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :chrome-bg)
  (shadow.cljs.devtools.api/compile :chrome-content))

