(ns shadow.cljs.devtools.config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [shadow.build.config :as build-config]
    [shadow.cljs.util :as cljs-util]
    [shadow.cljs.config-env :as config-env]
    [shadow.jvm-log :as log]))

(s/def ::builds (s/map-of keyword? ::build-config/build))

(s/def ::source-paths
  (s/coll-of string? :kind vector?))

(s/def ::dependency
  (s/cat
    :artifact
    symbol?
    :version
    string?

    ;; FIXME: properly do :exclusions and other things that may be allowed?
    :rest
    (s/* any?)))

(s/def ::dependencies
  (s/coll-of ::dependency :kind vector?))

(s/def ::aliases
  (s/coll-of keyword? :kind vector?))

(s/def ::deps-map
  (s/and
    (s/map-of keyword? any?)
    (s/keys
      ::opts-un
      [::aliases])))

(s/def ::deps
  (s/or
    :boolean boolean?
    :map ::deps-map))

(s/def ::config
  (s/keys
    :req-un
    [::builds]
    :opt-un
    [::source-paths
     ::dependencies
     ::deps]
    ))

(defn builds->map [builds]
  (cond
    (empty? builds)
    {}

    (vector? builds)
    (reduce
      (fn [builds {:keys [id] :as build}]
        (assoc builds id (assoc build :build-id id)))
      {}
      builds)

    ;; ensure that each build has an :build-id so user isn't forced to repeat it
    (map? builds)
    (reduce-kv
      (fn [builds id build]
        (assoc builds id (assoc build :build-id id)))
      {}
      builds)

    :else
    (throw (ex-info "invalid builds entry" {:builds builds}))
    ))

(defn normalize [cfg]
  (cond
    (vector? cfg)
    (recur {:builds cfg})

    (map? cfg)
    (update cfg :builds builds->map)

    :else
    (throw (ex-info "invalid config" {:cfg cfg}))
    ))

(def default-config
  {:cache-root ".shadow-cljs"
   :builds {}})

(def default-builds
  {:npm
   ^:generated
   {:build-id :npm
    :target :npm-module
    :output-dir "node_modules/shadow-cljs"}})

(defn read-config-str [s]
  (edn/read-string
    {:readers
     {'shadow/env config-env/read-env
      'env config-env/read-env}}
    s))

(defn read-config [file]
  (-> file
      (slurp)
      (read-config-str)))

(defn load-user-env-config [env-key]
  (let [env-val (System/getenv env-key)]
    (when (seq env-val)
      (let [file (io/file env-val "shadow-cljs" "config.edn")]
        (when (.exists file)
          (read-config file))))))

(defn load-user-home-config []
  (let [file (io/file (System/getProperty "user.home") ".shadow-cljs" "config.edn")]
    (when (.exists file)
      (read-config file))))

(defn load-user-config []
  (or (load-user-env-config "XDG_CONFIG_HOME")
      (load-user-env-config "LOCALAPPDATA")
      (load-user-home-config)
      {}))

(defn load-cljs-edn []
  (let [file (io/file "shadow-cljs.edn")]
    (if-not (.exists file)
      default-config
      (-> (read-config file)
          (normalize)
          (->> (merge default-config))
          (update :builds #(merge default-builds %))
          (assoc :user-config (load-user-config))
          ))))

(defn load-cljs-edn! []
  (let [config (load-cljs-edn)]
    (when-not (s/valid? ::config config)
      (s/explain ::config config)
      (throw (ex-info "invalid config" (s/explain-data ::config config))))
    config
    ))

(comment
  ;; ok
  (s/valid? ::config '{:builds {} :deps {:aliases [:a :b :c]}})
  (s/valid? ::config '{:builds {} :deps true})
  (s/valid? ::config '{:builds {} :deps false})
  ;; not
  (s/valid? ::config '{:builds {} :deps {foo {:mvn/version "1.2.3"}}}))

(defn get-build
  ([id]
   (get-build (load-cljs-edn!) id))
  ([config id]
   (get-in config [:builds id])))

(defn get-build! [id]
  (or (get-build id)
      (throw (ex-info (str "no build with id: " id) {:tag ::no-build :id id}))))

(defn make-cache-dir [cache-root build-id mode]
  {:pre [(cljs-util/is-file-instance? cache-root)
         (keyword? build-id)
         (keyword? mode)]}
  (io/file cache-root "builds" (name build-id) (name mode)))
