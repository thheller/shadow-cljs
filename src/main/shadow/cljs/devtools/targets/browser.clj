(ns shadow.cljs.devtools.targets.browser
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.spec :as s]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.server.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.server.config :as config]
            [clojure.data.json :as json]))

(s/def ::entries
  (s/coll-of symbol? :kind vector?))

(s/def ::public-path
  shared/non-empty-string?)

(s/def ::prepend
  shared/non-empty-string?)

(s/def ::depends-on
  (s/coll-of keyword? :kind set?))

(s/def ::module
  (s/keys
    :req-un
    [::entries]
    :opt-un
    [::depends-on
     ::prepend]))

(s/def ::modules
  (s/map-of
    keyword?
    ::module))

(s/def ::target
  (s/keys
    :req-un
    [::modules]
    :opt-un
    [::public-dir
     ::public-path]
    ))

(defmethod config/target-spec :browser [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries (or depends-on #{}) module-config))
    state
    modules))

(defn json [obj]
  (json/write-str obj :escape-slash false))

;; FIXME: really not sure how I feel about this
(defn create-loader [{:keys [public-path modules] :as state} loader-config]
  (let [loader-config
        (cond
          (true? loader-config)
          {:name :loader
           :entries []}
          (map? loader-config)
          loader-config
          :else
          (throw (ex-info "invalid loader config" {:config loader-config})))

        loader-name
        (or (:name loader-config) :loader)

        module-infos
        (reduce-kv
          (fn [mi mod-name mod]
            (assoc mi (name mod-name)
              (->> mod
                   :depends-on
                   (remove #{loader-name})
                   (map name)
                   (into []))))
          {}
          modules)

        module-uris
        (reduce-kv
          (fn [mi mod-name mod]
            (assoc mi (name mod-name) (str public-path "/" (:js-name mod))))
          {}
          modules)

        modules
        (reduce-kv
          (fn [mi mod-name mod]
            (assoc mi mod-name
              (-> mod
                  (dissoc :default)
                  (update :append-js str "\nshadow.loader.set_loaded('" (name mod-name) "');\n")
                  (update :depends-on conj loader-name)
                  )))
          {}
          modules)]

    (-> state
        (assoc :modules modules)
        (dissoc :default-module)
        (cljs/configure-module loader-name (into ['shadow.loader] (:entries loader-config)) #{}
          {:append-js
           (str "shadow.loader.setup(" (json module-uris) ", " (json module-infos) ");")}))))

(defn init [state mode {:keys [modules loader] :as config}]
  (let [{:keys [public-dir public-path]}
        (merge default-browser-config config)]

    (-> state
        (cond->
          public-path
          (cljs/merge-build-options {:public-path public-path})

          public-dir
          (cljs/merge-build-options {:public-dir (io/file public-dir)}))

        (configure-modules modules)
        (cond->
          loader
          (create-loader loader)))))

(defn flush [state mode config]
  (case mode
    :dev
    (if (:loader config)
      (cljs/flush-unoptimized-compact state)
      (cljs/flush-unoptimized state))
    :release
    (cljs/flush-modules-to-disk state)))

(defn process
  [{::comp/keys [stage mode config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))
