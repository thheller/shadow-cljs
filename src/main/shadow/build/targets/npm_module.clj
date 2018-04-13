(ns shadow.build.targets.npm-module
  (:refer-clojure :exclude (flush require resolve))
  (:require [shadow.build :as comp]
            [shadow.build.modules :as modules]
            [shadow.build.api :as build-api]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [shadow.build.output :as output]
            [shadow.build.targets.shared :as shared]
            [shadow.build.classpath :as cp]
            [shadow.cljs.repl :as repl]
            [shadow.build.targets.browser :as browser])
  (:import [java.net NetworkInterface InetAddress Inet4Address]))

(defn configure [state mode {:keys [runtime entries output-dir] :as config}]
  (-> state
      (build-api/with-build-options
        {:module-format :js
         ;; only greedy if no entries were specified
         ;; since that means add everything
         :greedy (nil? entries)
         :dynamic-resolve true})
      (build-api/with-js-options
        {:js-provider :require})

      (build-api/configure-modules
        {:main
         {:entries []
          :depends-on #{}
          :expand true}})

      (cond->
        output-dir
        (build-api/with-build-options {:output-dir (io/file output-dir)})

        (and (= :dev mode) (:worker-info state))
        (-> (repl/setup)
            (shared/merge-repl-defines
              (update config :devtools merge {:autolaod false ;; doesn't work yet, use built-in for now
                                              :use-document-host false})))
        )))

(defn resolve* [module-config {:keys [classpath] :as state} mode {:keys [entries runtime] :as config}]
  (let [repl?
        (some? (:worker-info state))

        entries
        (-> (or entries
                ;; if the user didn't specify any entries we default to compiling everything in the source-path (but not in jars)
                (cp/get-source-provides classpath))

            ;; just to ensure entries are never empty
            (conj 'cljs.core))]

    (-> module-config
        (assoc :entries (vec entries))
        (cond->
          (and repl? (or (= :browser runtime) (nil? runtime)))
          (-> (browser/inject-repl-client state config)
              (browser/inject-devtools-console state config))

          (and repl? (= :node runtime))
          (update :entries shared/prepend '[cljs.user shadow.cljs.devtools.client.node])

          (and repl? (= :react-native runtime))
          (-> (update :entries shared/prepend '[cljs.user shadow.cljs.devtools.client.react-native])
              ;; rn itself doesn't support this but the remote debug chrome thing does
              (browser/inject-devtools-console state config)))

        )))

(defn resolve [state mode config]
  (-> state
      (update-in [::modules/config :main] resolve* state mode config)
      (modules/analyze)))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :resolve
    (resolve state mode config)

    :flush
    (case mode
      :dev
      (output/flush-dev-js-modules state mode config)
      :release
      (output/flush-optimized state))

    state
    ))
