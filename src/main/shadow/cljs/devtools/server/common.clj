(ns shadow.cljs.devtools.server.common
  (:require [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [shadow.cljs.devtools.server.js-watch :as js-watch]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.server.reload-classpath :as reload-classpath]
            [shadow.cljs.devtools.server.reload-npm :as reload-npm]
            [shadow.cljs.devtools.server.reload-macros :as reload-macros]
            [cognitect.transit :as transit]
            [clojure.edn :as edn]
            [shadow.build]
            [shadow.build.api :as cljs]
            [shadow.build.classpath :as build-classpath]
            [shadow.build.npm :as build-npm]
            [clojure.java.io :as io]
            [clojure.java.classpath :as cp]
            [shadow.cljs.devtools.server.config-watch :as config-watch]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.system-bus :as system-bus])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.util.concurrent Executors)))

;; FIXME: make config option
(def classpath-excludes
  [#"resources(/?)$"
   #"classes(/?)$"
   #"java(/?)$"])

(defn get-classpath-directories []
  (->> (cp/classpath)
       ;; (remove #(cljs/should-exclude-classpath classpath-excludes %))
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

         :cache-root
         {:depends-on [:config]
          :start (fn [{:keys [cache-root]}]
                   (io/file cache-root))
          :stop (fn [cache-root])}

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

         :reload-classpath
         {:depends-on [:system-bus :classpath]
          :start reload-classpath/start
          :stop reload-classpath/stop}

         :reload-npm
         {:depends-on [:system-bus :npm]
          :start reload-npm/start
          :stop reload-npm/stop}

         :reload-macros
         {:depends-on [:system-bus]
          :start reload-macros/start
          :stop reload-macros/stop}

         :supervisor
         {:depends-on [:system-bus :executor :cache-root :http :classpath :npm]
          :start super/start
          :stop super/stop}

         :classpath
         {:depends-on [:cache-root]
          :start (fn [cache-root]
                   (-> (build-classpath/start cache-root)
                       (build-classpath/index-classpath)))
          :stop build-classpath/stop}

         :npm
         {:depends-on []
          :start build-npm/start
          :stop build-npm/stop}}

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
                            (fs-watch/start
                              (get-classpath-directories)
                              ;; no longer watches .clj files, reload-macros directly looks at used macros
                              ["cljs" "cljc" "js"]
                              #(system-bus/publish! system-bus ::sys-msg/cljs-watch {:updates %})
                              ))
                   :stop fs-watch/stop}})
          ))))
