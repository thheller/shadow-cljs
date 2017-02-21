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
  (let [input (load-cljs-edn)
        config (s/conform ::config input)]
    (when (= config ::s/invalid)
      (s/explain ::config input)
      (throw (ex-info "invalid config" (s/explain-data ::config input))))
    config
    ))
