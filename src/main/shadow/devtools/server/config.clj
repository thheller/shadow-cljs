(ns shadow.devtools.server.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec :as s]
            [shadow.devtools.spec.build :as s-build]))

(s/def ::config
  (s/coll-of ::s-build/build
    :kind vector?
    :distinct true?))

(defn load-cljs-edn []
  (-> (io/file "cljs.edn")
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

