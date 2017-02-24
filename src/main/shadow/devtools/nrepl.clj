(ns shadow.devtools.nrepl
  (:require [clojure.tools.nrepl.middleware :as middleware]
            [shadow.devtools.embedded :as cljs]
            [shadow.devtools.server.supervisor :as super]))

;; FIXME: HEADACHES!

(defonce redirect-to (volatile! nil))

(defn redirect! [build-id]
  (let [{:keys [supervisor] :as sys}
        (cljs/system)

        worker-proc
        (when supervisor
          (super/get-worker supervisor build-id))]
    (if (nil? worker-proc)
      nil
      (do (vreset! redirect-to build-id)
          (println "now redirecting repl commands to " build-id)
          ::redirect)
      )))

(defn release! []
  (vreset! redirect-to nil))

(defn handle-msg
  [{:keys [op] :as msg} continue]
  (when @redirect-to
    (.. System -out (println (pr-str msg))))

  (continue msg))

(defn inject-devtools [continue]
  (fn [{:keys [session op] :as msg}]
    (#'handle-msg msg continue)))

;; FIXME: figure out what all this means
(middleware/set-descriptor!
  #'inject-devtools
  {:require
   #{"clone"}

   :expects
   #{"eval"
     "load-file"}

   :handles
   {}})
