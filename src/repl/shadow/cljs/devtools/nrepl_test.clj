(ns shadow.cljs.devtools.nrepl-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.tools.nrepl :as client]
            [clojure.tools.nrepl.server :as server]
            [shadow.cljs.devtools.embedded :as cljs]
            [shadow.cljs.devtools.nrepl :as dt-nrepl]
            [clojure.edn :as edn]))

(def TEST-PORT 55555)

(defn call [conn op args]
  (-> (client/client conn 2000)
      (client/message {:op op :args (pr-str args)})
      (client/response-values)
      (as-> x
        (map edn/read-string x))))

(defn start-server []
  (server/start-server
    :port TEST-PORT
    :handler (server/default-handler
               #'dt-nrepl/wrap-devtools)))

(deftest test-nrepl-setup
  (cljs/start! {:verbose true})
  (with-open [srv (start-server)]
    (with-open [conn (client/connect :port TEST-PORT)]
      ;; (pprint (call conn :cljs/list-builds {}))
      (pprint (call conn :cljs/start-worker {:build :browser :autobuild true}))
      (pprint (call conn :cljs/sync {:build :browser}))
      ;; (pprint (call conn :cljs/start-build {:build :script :opts {:autobuild true}}))
      ))
  (cljs/stop!))



(comment
  (cljs/start! {:verbose true})
  (cljs/stop!)

  (def server (start-server))

  (.close server))

