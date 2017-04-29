(ns shadow.cljs.devtools.nrepl
  (:require [clojure.pprint :refer (pprint)]
            [clojure.tools.nrepl.middleware :as middleware]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.misc :as misc]
            [shadow.cljs.devtools.embedded :as cljs]
            [shadow.cljs.devtools.config :as config]
            [clojure.edn :as edn]))

(defn do-eval [msg args]
  [:foo])

(defn do-list-builds [msg args]
  [{:builds (config/load-cljs-edn)}])

(defn do-start-worker
  [msg args]
  [(cljs/start-worker (:build args) args)])

(defn do-sync
  [msg {:keys [build] :as args}]
  [(cljs/sync build)])

(defn rpc [{:keys [op transport args] :as msg} action]
  (try
    (let [replies (action msg (when args (edn/read-string args)))]
      (doseq [reply replies]
        (transport/send transport
          (misc/response-for msg {:value (pr-str reply)}))))
    (catch Exception e
      (prn [:error-while-processing op e])
      (transport/send transport
        (misc/response-for msg {:status :error :error (.getMessage e)}))))
  (transport/send transport
    (misc/response-for msg {:status :done})))

(defn wrap-devtools
  "Middleware that looks up possible functions for the given (partial) symbol."
  [next]
  (fn [{:keys [op] :as msg}]
    (-> msg
        (dissoc :transport :session)
        (pprint))
    (case op
      "cljs/eval"
      (rpc msg do-eval)
      "cljs/list-builds"
      (rpc msg do-list-builds)
      "cljs/start-worker"
      (rpc msg do-start-worker)
      "cljs/sync"
      (rpc msg do-sync)
      ;; default
      (next msg))))

(middleware/set-descriptor!
  #'wrap-devtools
  {:handles
   {"cljs/eval" {}}})