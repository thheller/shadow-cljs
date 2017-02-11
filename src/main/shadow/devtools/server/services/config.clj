(ns shadow.devtools.server.services.config
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [shadow.devtools.server.util :as util]
            [clojure.edn :as edn]
            [clojure.spec :as spec]
            [shadow.devtools.spec.build :as s-build]
            ))

(spec/def ::config (spec/+ ::s-build/build))

(defn load-cljs-edn []
  (-> (io/file "cljs.edn")
      (slurp)
      (edn/read-string)))

(defn load-cljs-edn! []
  (let [input (load-cljs-edn)
        config (spec/conform ::config input)]
    (when (= config ::spec/invalid)
      (spec/explain ::config input)
      (throw (ex-info "invalid config" (spec/explain-data ::config input))))

    config
    ))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn get-configured-builds [svc]
  (:builds svc))

(defn start []
  {::service true
   :builds (load-cljs-edn!)})

(defn stop [svc])

