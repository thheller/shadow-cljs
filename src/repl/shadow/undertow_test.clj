(ns shadow.undertow-test
  (:require
    [clojure.test :as ct :refer (deftest is)]
    [shadow.undertow :as u]
    [clojure.java.io :as io]
    [shadow.undertow.impl :as impl])
  (:import [io.undertow.server HttpHandler]
           [io.undertow.server.handlers.resource ClassPathResourceManager ResourceHandler PathResourceManager]
           [java.io File]
           [io.undertow Handlers]
           [io.undertow.server.handlers BlockingHandler]))


(deftest undertow-handlers
  (let [http-handler-fn
        (fn [ring-map] {:status 200})

        root-dir
        (io/file "tmp")

        state
        (u/build {}
          [::u/ws-upgrade
           ;; "Upgrade" requests
           [::u/ring {:handler http-handler-fn}]
           ;; normal
           [::u/classpath {:root "some/path"}
            [::u/file {:root-dir root-dir}
             [::u/classpath {:root "shadow/cljs/devtools/server/dev_http"}
              [::u/disable-cache
               [::u/blocking
                [::u/ring {:handler http-handler-fn}]]]]]]])]

    (prn state)

    (u/close-handlers state)
    ))
