(ns shadow.build.cache
  (:require [cognitect.transit :as transit])
  (:import (java.io File FileOutputStream FileInputStream ByteArrayOutputStream)
           (java.net URL)
           (cljs.tagged_literals JSValue)))

(defn write-stream [out data]
  (let [w (transit/writer out :json
            {:handlers
             {URL
              (transit/write-handler "url" str)
              File
              (transit/write-handler "file" #(.getAbsolutePath %))
              JSValue
              (transit/write-handler "js-value" #(.-val %))
              }})]
    (transit/write w data)))

(defn write-file [^File file data]
  (with-open [out (FileOutputStream. file)]
    (write-stream out data)))

(defn write-str [data]
  (let [out (ByteArrayOutputStream.)]
    (write-stream out data)
    (.toString out "UTF-8")))

(defn read-cache [^File file]
  {:pre [(.exists file)]}
  (with-open [in (FileInputStream. file)]
    (let [r (transit/reader in :json
              {:handlers
               {"url"
                (transit/read-handler #(URL. %))
                "file"
                (transit/read-handler #(File. %))
                "js-value"
                (transit/read-handler #(JSValue. %))
                }})]
      (transit/read r)
      )))


