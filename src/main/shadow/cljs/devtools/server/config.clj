(ns shadow.cljs.devtools.server.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec :as s]))

(s/def ::id keyword?)

(s/def ::target
  (s/or :keyword keyword?
        :symbol qualified-symbol?))

(defmulti target-spec :target :default ::default)

(defmethod target-spec ::default [_]
  (s/spec any?))

(s/def ::build
  (s/and
    (s/keys
      :req-un
      [::id
       ::target])
    (s/multi-spec target-spec :target)))

(s/def ::config
  (s/coll-of ::build :kind vector?))

(defn load-cljs-edn []
  (-> (io/file "shadow-cljs.edn")
      (slurp)
      (edn/read-string)))

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

