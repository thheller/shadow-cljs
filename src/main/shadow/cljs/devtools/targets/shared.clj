(ns shadow.cljs.devtools.targets.shared
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.build :as cljs]
            ))

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

;; removed based on feedback, too much magic ... too little value.
;; still might re-use some parts later
(comment

  (defn is-npm-ns? [x]
    (str/starts-with? (str x) "npm."))

  (defn has-npm-require? [{:keys [requires] :as rc}]
    (some is-npm-ns? requires))

  (defn add-npm-ns-entry [all ns]
    (if (contains? all ns)
      all
      (assoc all ns #{})))

  (defn extract-npm-namespaces* [namespaces {:keys [type ns-info requires] :as rc}]
    (case type
      :js
      ;; FIXME: this will break if JS sources goog.require("npm.react.Component") where Compoent is part of npm.react
      (reduce add-npm-ns-entry namespaces requires)

      :cljs
      (let [{:keys [imports requires]}
            ns-info

            npm-imports
            (->> imports
                 (filter (comp is-npm-ns? second))
                 (into {})
                 (set/map-invert))

            npm-requires
            (->> requires
                 (vals)
                 (filter is-npm-ns?)
                 (remove npm-imports)
                 (into #{}))

            namespaces
            (reduce add-npm-ns-entry namespaces npm-requires)

            namespaces
            (reduce-kv
              (fn [all fqn class]
                ;; FIXME: feels like imports {Component npm.react.Component} should be #{npm.react/Component}?
                ;; the map is inverted above so we have {npm.react.Component Component}
                ;; need to extra npm.react and then add the extra import to namespaces map
                (let [class-str
                      (str class)
                      fqn-str
                      (str fqn)

                      pkg
                      (-> fqn-str
                          (subs 0 (- (count fqn-str)
                                     1 ;; the dot
                                     (count class-str)))
                          (symbol))]

                  (update all pkg conj fqn)))
              namespaces
              npm-imports)]
        namespaces)))

  (defn extract-npm-namespaces [state]
    (->> (:sources state)
         (vals)
         (filter has-npm-require?)
         (reduce extract-npm-namespaces* {})))

  (defn npm-aliases [state require?]
    (let [npm-namespaces
          (extract-npm-namespaces state)]

      (reduce-kv
        (fn [state alias provides]
          (let [module-name
                (-> (str alias)
                    (subs 4)
                    (str/replace #"\." "/"))

                ;; npm.react -> react
                ;; npm.foo.bar -> foo/bar

                provide
                (cljs-comp/munge alias)

                rc
                {:type :js
                 :name (str alias ".js")
                 :provides (conj provides alias)
                 :requires #{}
                 :require-order []
                 :js-name (str alias ".js")
                 :input (atom (str "goog.provide(\"" provide "\");\n"
                                   (->> provides
                                        (map (fn [provide]
                                               (str "goog.provide(\"" provide "\");")))
                                        (str/join "\n"))
                                   "\n"
                                   (if require?
                                     (str provide " = require(\"" module-name "\");\n")
                                     (str provide " = window[\"npm$modules\"][\"" module-name "\"];\n"))))
                 :last-modified 0}]

            (cljs/merge-resource state rc)
            ))
        state
        npm-namespaces))))