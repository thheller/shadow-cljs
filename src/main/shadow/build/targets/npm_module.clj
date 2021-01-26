(ns shadow.build.targets.npm-module
  (:refer-clojure :exclude (flush require resolve))
  (:require [shadow.build :as comp]
            [shadow.build.modules :as modules]
            [shadow.build.api :as build-api]
            [clojure.java.io :as io]
            [shadow.build.output :as output]
            [shadow.build.targets.shared :as shared]
            [shadow.build.classpath :as cp]
            [shadow.cljs.repl :as repl]
            [shadow.build.targets.browser :as browser]
            [clojure.spec.alpha :as s]
            [shadow.build.config :as config]
            [shadow.build.node :as node]
            [shadow.build.classpath :as classpath]
            [shadow.build.test-util :as tu]))

(s/def ::runtime #{:node :browser :react-native})

(s/def ::entries
  (s/coll-of simple-symbol? :kind vector? :distinct true))

(s/def ::target
  (s/keys
    :req-un
    [::shared/output-dir
     ]
    :opt-un
    [::entries
     ::runtime
     ::shared/devtools]))

(defmethod config/target-spec :npm-module [_]
  (s/spec ::target))

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

        (= :node runtime)
        (node/set-defaults)

        (and (= :dev mode) (:worker-info state))
        (shared/merge-repl-defines config)
        )))

(defn resolve* [module-config {:keys [classpath] :as state} mode {:keys [entries runtime] :as config}]
  (let [repl?
        (some? (:worker-info state))

        entries
        (cond
          (seq entries)
          (vec entries)

          ;; FIXME: this isn't really about testing but the options may fit
          (contains? config :ns-regexp)
          (tu/find-test-namespaces classpath config)

          :all
          (classpath/find-cljs-namespaces-in-files classpath nil))]

    (-> module-config
        (assoc :entries entries)
        (cond->
          (and repl? (or (= :browser runtime) (nil? runtime)))
          (-> (browser/inject-repl-client state config)
              (browser/inject-devtools-console state config))

          (and repl? (= :node runtime))
          (update :entries shared/prepend '[shadow.cljs.devtools.client.node])

          (and repl? (= :react-native runtime))
          (-> (update :entries shared/prepend '[shadow.cljs.devtools.client.react-native])
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

    :compile-prepare
    (node/replace-goog-global state)

    :flush
    (case mode
      :dev
      (output/flush-dev-js-modules state mode config)
      :release
      (output/flush-optimized state))

    state
    ))
