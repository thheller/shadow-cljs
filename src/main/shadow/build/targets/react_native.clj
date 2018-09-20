(ns shadow.build.targets.react-native
  (:refer-clojure :exclude (flush))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [clojure.spec.alpha :as s]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.build :as comp]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.build.api :as build-api]
            [shadow.build.modules :as modules]
            [shadow.build.output :as output]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.cljs.util :as util]
            [shadow.cljs.devtools.api :as api]
            [shadow.build.log :as build-log]))

(s/def ::init-fn qualified-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::init-fn
     ::shared/output-to]
    ))

(defmethod config/target-spec :react-native [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defmethod build-log/event->str ::server-addr
  [{:keys [addr]}]
  (format "Using IP: %s" addr))

(defn set-server-host [state {:keys [local-ip] :as config}]
  (let  [server-addr (or local-ip (api/get-server-addr))]

    (util/log state {:type ::server-addr :addr server-addr})

    (assoc-in state
      [:compiler-options :closure-defines 'shadow.cljs.devtools.client.env/server-host]
      (str server-addr))))

(defn configure [state mode {:keys [build-id init-fn output-to] :as config}]
  (let [output-file
        (io/file output-to)

        dev?
        (= :dev mode)

        output-dir
        (if dev?
          (io/file (.getParentFile output-file) "cljs-dev" (name build-id))
          (io/file (.getParentFile output-file)))]

    (-> state
        (build-api/with-build-options {:output-dir output-dir})
        (cond->
          dev?
          (assoc-in [:build-options :module-format] :js))

        (assoc ::output-file output-file
               ::init-fn init-fn)

        (build-api/configure-modules
          {:index (-> {:entries [(output/ns-only init-fn)]}
                      (cond->
                        (not dev?)
                        (assoc :append-js (output/fn-call init-fn))))})

        (update :js-options merge {:js-provider :require})
        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "react-native")

        (cond->
          (:worker-info state)
          (-> (shared/merge-repl-defines config)
              (set-server-host config)
              (update-in [::modules/config :index :entries] shared/prepend
                '[cljs.user
                  shadow.cljs.devtools.client.react-native]))))))

(defn flush-dev-index [{::keys [output-file] :as state} {:keys [build-id init-fn] :as config}]
  (spit output-file
    (str "var CLJS = require(\"./cljs-dev/" (name build-id) "/cljs_env.js\");\n"
         (->> (:build-sources state)
              (remove #{output/goog-base-id})
              (map #(data/get-source-by-id state %))
              (map :output-name)
              (map #(str "require(\"./cljs-dev/" (name build-id) "/" % "\");" ))
              (str/join "\n"))
         "\nCLJS." (cljs-comp/munge init-fn) "();")))

(defn flush [state mode config]
  (case mode
    :dev
    (do (flush-dev-index state config)
        (output/flush-dev-js-modules state mode config))
    :release
    (output/flush-optimized state))

  state)

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :flush
    (flush state mode config)

    state
    ))

(comment
  (shadow.cljs.devtools.api/compile :expo-ios))

(comment
  (shadow.cljs.devtools.api/watch :expo-ios {:verbose true}))