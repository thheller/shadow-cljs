(ns shadow.cljs.devtools.server.common
  (:require [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [shadow.cljs.devtools.server.js-watch :as js-watch]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [cognitect.transit :as transit]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.compiler]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.java.classpath :as cp]
            [shadow.cljs.devtools.server.config-watch :as config-watch]
            [shadow.cljs.devtools.server.supervisor :as super])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.util.concurrent Executors)))

;; FIXME: make config option
(def classpath-excludes
  [#"resources(/?)$"
   #"classes(/?)$"
   #"java(/?)$"])

(defn get-classpath-directories []
  (->> (cp/classpath)
       (remove #(cljs/should-exclude-classpath classpath-excludes %))
       (filter #(.isDirectory %))
       (map #(.getCanonicalFile %))
       (distinct)
       (into [])))


(defn app [config]
  (let [watch-mode
        :clj #_(get config :watch true)]

    (-> {:edn-reader
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

         :executor
         {:depends-on []
          :start
          (fn []
            (let [n-threads (.. Runtime getRuntime availableProcessors)]
              (Executors/newFixedThreadPool n-threads)))
          :stop
          (fn [ex]
            (.shutdown ex))}

         :system-bus
         {:depends-on []
          :start sys-bus/start
          :stop sys-bus/stop}

         :config-watch
         {:depends-on [:system-bus]
          :start config-watch/start
          :stop config-watch/stop}

         :supervisor
         {:depends-on [:system-bus :executor :http]
          :start super/start
          :stop super/stop}
         }

        (cond->
          (= :js watch-mode)
          (merge {:js-watch
                  {:depends-on [:system-bus]
                   :start js-watch/start
                   :stop js-watch/stop}})

          (= :clj watch-mode)
          (merge {:cljs-watch
                  {:depends-on [:system-bus]
                   :start (fn [system-bus]
                            (fs-watch/start system-bus ::sys-msg/cljs-watch
                              (get-classpath-directories)
                              ["cljs" "cljc" "clj" "js"]))
                   :stop fs-watch/stop}})
          ))))
