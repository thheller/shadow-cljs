(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require [shadow.runtime.services :as rt]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.errors :as e]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.compiler :as comp]))

(def default-opts
  {:autobuild true})

(def cli-spec
  [["-b" "--build BUILD-ID" "use build defined in shadow-cljs.edn"
    :parse-fn keyword]
   [nil "--dev" "compile once and watch"
    :default true]
   [nil "--once" "compile once and exit"]
   [nil "--release" "compile in release mode and exit"]
   [nil "--debug" "debug mode, useful in combo with --release (pseudo-names, source-map)"]
   [nil "--check" "run sources through closure compiler type checks"]
   [nil "--npm" "run in npm compatibility mode"]
   [nil "--runtime TARGET" "(npm-only) node or browser"
    :parse-fn keyword
    :default :node]
   ["-e" "--entries NAMESPACES" "(npm-only) comma seperated list of CLJS entry namespaces"
    :parse-fn #(->> (str/split % #",") (map symbol) (into []))]
   ["-v" "--verbose"]
   ["-h" "--help"]])

(defn help [{:keys [errors summary] :as opts}]
  (do (doseq [err errors]
        (println err))
      (println "Command line args:")
      (println "-----")
      (println summary)
      (println "-----")))

(defn main [& args]
  (let [{:keys [options summary errors] :as opts}
        (cli/parse-opts args cli-spec)]


    (if (or (:help options) (seq errors))
      (help opts)

      (let [{:keys [build npm]} options

            build-config
            (cond
              (keyword? build)
              (api/get-build-config build)

              npm ;; ad-hoc npm config
              (let [{:keys [entries once]} options]
                (merge (select-keys options [:entries :verbose :runtime])
                       {:id :npm-module :target :npm-module}))

              :else
              nil)]

        (if-not (some? build-config)
          (do (println "Please use specify a build or use --npm")
              (help opts))

          (let [{:keys [check dev once release]} options]
            (cond
              release
              (api/release* build-config options)

              once
              (api/once* build-config options)

              check
              (api/check* build-config options)

              :else
              (api/dev* build-config (merge default-opts options))
              )))))))

(defn -main [& args]
  (apply main args))

(comment
  ;; FIXME: fix these properly and create CLI args for them
  (defn autotest
    "no way to interrupt this, don't run this in nREPL"
    []
    (-> (api/test-setup)
        (cljs/watch-and-repeat!
          (fn [state modified]
            (-> state
                (cond->
                  ;; first pass, run all tests
                  (empty? modified)
                  (node/execute-all-tests!)
                  ;; only execute tests that might have been affected by the modified files
                  (not (empty? modified))
                  (node/execute-affected-tests! modified))
                )))))

  (defn test-all []
    (api/test-all))

  (defn test-affected [test-ns]
    (api/test-affected [(cljs/ns->cljs-file test-ns)])))