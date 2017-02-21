(ns shadow.devtools.server.common
  (:require [shadow.devtools.server.services.supervisor :as super]
            [shadow.devtools.server.services.fs-watch :as fs-watch]
            [cognitect.transit :as transit]
            [clojure.edn :as edn]

    ;; these are unused but must be imported for the comp/process defmulti
            [shadow.devtools.server.compiler]
            [shadow.devtools.server.compiler.browser]
            [shadow.devtools.server.compiler.library]
            [shadow.devtools.server.compiler.script])
  (:import (java.io ByteArrayOutputStream InputStream)))

(defn app []
  {:edn-reader
   {:depends-on []
    :start
    (fn []
      (fn [input]
        (cond
          (instance? String input)
          (edn/read-string input)
          (instance? InputStream input)
          (edn/read input)
          :else
          (throw (ex-info "dunno how to read" {:input input})))))
    :stop (fn [reader])}

   :transit-str
   {:depends-on []
    :start
    (fn []
      (fn [data]
        (let [out (ByteArrayOutputStream. 4096)
              w (transit/writer out :json)]
          (transit/write w data)
          (.toString out)
          )))

    :stop (fn [x])}

   :fs-watch
   {:depends-on []
    :start fs-watch/start
    :stop fs-watch/stop}
   })
