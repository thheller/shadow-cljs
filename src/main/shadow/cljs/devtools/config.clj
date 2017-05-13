(ns shadow.cljs.devtools.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]))

(s/def ::id keyword?)

(s/def ::target
  #(or (simple-keyword? %)
       (symbol? %)))

(defmulti target-spec :target :default ::default)

(defmethod target-spec ::default [_]
  (s/spec any?))

(s/def ::build
  (s/keys
    :req-un
    [::id
     ::target]))

(s/def ::build+target
  (s/and
    ::build
    (s/multi-spec target-spec :target)))

(s/def ::config
  (s/coll-of ::build :kind vector?))

(defn load-cljs-edn []
  (let [file (io/file "shadow-cljs.edn")]
    (if-not (.exists file)
      [] ;; FIXME: throw instead? we can't do anything without configured builds
      (-> file (slurp) (edn/read-string)))))

(defn load-cljs-edn! []
  (let [config (load-cljs-edn)]
    (when-not (s/valid? ::config config)
      (s/explain ::config config)
      (throw (ex-info "invalid config" (s/explain-data ::config config))))
    config
    ))

(defn get-build!
  ([id]
   (get-build! (load-cljs-edn!) id))
  ([config id]
   (let [build
         (->> config
              (filter #(= id (:id %)))
              (first))]
     (when-not build
       (throw (ex-info (str "no build with id: " id) {:id id})))
     build
     )))

