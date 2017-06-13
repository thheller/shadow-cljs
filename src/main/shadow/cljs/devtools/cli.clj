(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require [shadow.runtime.services :as rt]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.cli-opts :as opts]
            [clojure.repl :as repl]))

;; use namespaced keywords for every CLI specific option
;; since all options are passed to the api/* and should not conflict there

(def default-opts
  {:autobuild true})

(def default-npm-config
  {:id :npm
   :target :npm-module
   :runtime :node
   :output-dir "node_modules/shadow-cljs"})

(defn invoke
  "invokes a fn by requiring the namespace and looking up the var"
  [sym & args]
  ;; doing the delayed (require ...) so things are only loaded
  ;; when a path is reached as they load a bunch of code other paths
  ;; do not use, greatly improves startup time
  (let [require-ns (symbol (namespace sym))]
    (require require-ns)
    (let [fn-var (find-var sym)]
      (apply fn-var args)
      )))

(defn main [& args]
  (try
    (let [{:keys [options summary errors] :as opts}
          (opts/parse args)

          options
          (merge default-opts options)]

      (cond
        (or (::opts/help options) (seq errors))
        (opts/help opts)

        (= :server (::opts/mode options))
        (invoke 'shadow.cljs.devtools.server/from-cli options)

        :else
        (let [{::opts/keys [build npm]} options

              build-config
              (cond
                (keyword? build)
                (config/get-build! build)

                npm
                (merge default-npm-config (config/get-build :npm))

                :else
                nil)]

          (if-not (some? build-config)
            (do (println "Please use specify a build or use --npm")
                (opts/help opts))

            (case (::opts/mode options)
              :release
              (invoke 'shadow.cljs.devtools.api/release* build-config options)

              :check
              (invoke 'shadow.cljs.devtools.api/check* build-config options)

              :dev
              (invoke 'shadow.cljs.devtools.api/dev* build-config options)

              ;; make :once the default
              (invoke 'shadow.cljs.devtools.api/once* build-config options)
              )))))

    (catch Exception e
      (try
        (invoke 'shadow.cljs.devtools.errors/user-friendly-error e)
        (catch Exception e2
          (println "failed to format error because of:")
          (repl/pst e2)
          (flush)
          (println "actual error:")
          (repl/pst e)
          (flush)
          )))))

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