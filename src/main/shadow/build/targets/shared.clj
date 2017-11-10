(ns shadow.build.targets.shared
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [cljs.compiler :as cljs-comp]
            [shadow.build.api :as cljs]
            [shadow.build.data :as data]
            [shadow.build.modules :as modules]
            ))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))

(s/def ::output-dir non-empty-string?)

(s/def ::output-to non-empty-string?)

(defn prepend [tail head]
  {:pre [(vector? head)]}
  (into head tail))

(defn repl-defines
  [{:keys [worker-info] :as state} build-config]
  (let [{:keys [proc-id ssl host port]}
        worker-info

        {:keys [build-id]}
        build-config

        {:keys [reload-with-state before-load before-load-async after-load autoload]}
        (:devtools build-config)]

    {"shadow.cljs.devtools.client.env.enabled"
     true

     "shadow.cljs.devtools.client.env.autoload"
     (or autoload (some? before-load) (some? after-load))

     "shadow.cljs.devtools.client.env.module_format"
     (name (get-in state [:build-options :module-format]))

     "shadow.cljs.devtools.client.env.repl_host"
     host

     "shadow.cljs.devtools.client.env.repl_port"
     port

     "shadow.cljs.devtools.client.env.ssl"
     (true? ssl)

     "shadow.cljs.devtools.client.env.build_id"
     (name build-id)

     "shadow.cljs.devtools.client.env.proc_id"
     (str proc-id)

     "shadow.cljs.devtools.client.env.before_load"
     (when before-load
       (str (cljs-comp/munge before-load)))

     "shadow.cljs.devtools.client.env.before_load_async"
     (when before-load-async
       (str (cljs-comp/munge before-load-async)))

     "shadow.cljs.devtools.client.env.after_load"
     (when after-load
       (str (cljs-comp/munge after-load)))

     "shadow.cljs.devtools.client.env.reload_with_state"
     (boolean reload-with-state)
     }))


(defn inject-node-repl
  [state {:keys [devtools] :as config}]
  (if (false? (:enabled devtools))
    state
    (-> state
        (update-in [:compiler-options :closure-defines] merge (repl-defines state config))
        (update-in [::modules/config :main :entries] prepend '[cljs.user shadow.cljs.devtools.client.node])
        )))

(defn set-output-dir [state mode {:keys [id output-dir] :as config}]
  (-> state
      (cond->
        (seq output-dir)
        (assoc-in [:build-options :output-dir] (io/file output-dir))

        (not (seq output-dir))
        (assoc-in [:build-options :output-dir] (data/cache-file state "out")))))

