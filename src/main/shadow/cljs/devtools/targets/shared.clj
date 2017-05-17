(ns shadow.cljs.devtools.targets.shared
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [clojure.java.io :as io]
            [shadow.cljs.build :as cljs]
            [clojure.set :as set]))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))

(s/def ::public-dir non-empty-string?)

(s/def ::output-to non-empty-string?)

(defn prepend [tail head]
  {:pre [(vector? head)]}
  (into head tail))

(defn repl-defines
  [{:keys [worker-info] :as state} build-config]
  (let [{:keys [proc-id host port]}
        worker-info

        {:keys [id]}
        build-config

        {:keys [reload-with-state before-load after-load autoload]}
        (:devtools build-config)]

    {"shadow.cljs.devtools.client.env.enabled"
     true

     "shadow.cljs.devtools.client.env.autoload"
     (or autoload (some? before-load) (some? after-load))

     "shadow.cljs.devtools.client.env.repl_host"
     host

     "shadow.cljs.devtools.client.env.repl_port"
     port

     "shadow.cljs.devtools.client.env.build_id"
     (name id)

     "shadow.cljs.devtools.client.env.proc_id"
     (str proc-id)

     "shadow.cljs.devtools.client.env.before_load"
     (when before-load
       (str (cljs-comp/munge before-load)))

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
        (update :closure-defines merge (repl-defines state config))
        (update-in [:modules (:default-module state) :entries] prepend '[cljs.user shadow.cljs.devtools.client.node])
        )))

(defn set-public-dir [{:keys [work-dir] :as state} mode {:keys [id public-dir] :as config}]
  (-> state
      (cond->
        ;; FIXME: doesn't make sense to name this public-dir for node
        (seq public-dir)
        (assoc :public-dir (io/file public-dir))

        (not public-dir)
        (assoc :public-dir (io/file work-dir "shadow-cache" (name id) (name mode))))))

(defn npm-aliases [state require?]
  (let [npm-requires
        (->> (:sources state)
             (vals)
             (map :requires)
             (reduce set/union)
             (map str)
             (filter #(str/starts-with? % "npm."))
             (into []))]

    (reduce
      (fn [state alias]
        (let [module-name
              (-> (subs alias 4)
                  (str/replace #"\." "/"))

              ;; npm.react -> react
              ;; npm.foo.bar -> foo/bar

              provide
              (cljs-comp/munge alias)

              rc
              {:type :js
               :name (str alias ".js")
               :provides #{(symbol alias)}
               :requires #{}
               :require-order []
               :js-name (str alias ".js")
               :input (atom (str "goog.provide(\"" provide "\");\n"
                                 (if require?
                                   (str provide " = require(\"" module-name "\");\n")
                                   (str provide " = window[\"npm$modules\"][\"" module-name "\"];\n"))))
               :last-modified 0}]

          (cljs/merge-resource state rc)
          ))
      state
      npm-requires)))