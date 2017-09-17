(ns shadow.cljs.devtools.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [shadow.build.config :as build-config]))

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

(s/def ::config
  (s/keys
    :req-un
    [::builds]
    :opt-un
    [::source-paths
     ::dependencies]
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

    ;; ensure that each build has an :id so user isn't forced to repeat it
    (map? builds)
    (reduce-kv
      (fn [builds id build]
        (assoc builds id (assoc build :id id :build-id id)))
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
  {:cache-root "target/shadow-cljs"
   :builds {}})

(def default-builds
  {:npm {:id :npm
         :target :npm-module
         :output-dir "node_modules/shadow-cljs"}})

;; FIXME: memoize this!!! (should only repeat this if the config changes)
(defn load-cljs-edn []
  (let [file (io/file "shadow-cljs.edn")]
    (if-not (.exists file)
      default-config
      (-> file
          (slurp)
          (edn/read-string)
          (normalize)
          (->> (merge default-config))
          (update :builds #(merge default-builds %))
          ))))

(defn load-cljs-edn! []
  (let [config (load-cljs-edn)]
    (when-not (s/valid? ::config config)
      (s/explain ::config config)
      (throw (ex-info "invalid config" (s/explain-data ::config config))))
    config
    ))

(defn get-build
  ([id]
   (get-build (load-cljs-edn!) id))
  ([config id]
   (get-in config [:builds id])))

(defn get-build! [id]
  (or (get-build id)
      (throw (ex-info (str "no build with id: " id) {:tag ::no-build :id id}))))

