(ns shadow.cljs.cache
  (:require [cognitect.transit :as transit])
  (:import (java.io File FileOutputStream FileInputStream)
           (java.net URL)
           (cljs.tagged_literals JSValue)))

(defn write-cache [^File file data]
  (with-open [out (FileOutputStream. file)]
    (let [w (transit/writer out :json
              {:handlers
               {URL
                (transit/write-handler "url" str)
                JSValue
                (transit/write-handler "js-value" #(.-val %))
                }})]
      (transit/write w data)
      )))

(defn read-cache [^File file]
  {:pre [(.exists file)]}
  (with-open [in (FileInputStream. file)]
    (let [r (transit/reader in :json
              {:handlers
               {"url"
                (transit/read-handler #(URL. %))
                "js-value"
                (transit/read-handler #(JSValue. %))
                }})]
      (transit/read r)
      )))


