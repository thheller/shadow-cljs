(ns shadow.cljs.devtools.server.common
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [cognitect.transit :as transit]
    #_ [shadow.cljs.devtools.server.fs-watch :as fs-watch]
    [shadow.cljs.devtools.server.fs-watch-hawk :as fs-watch-hawk]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.devtools.server.system-msg :as sys-msg]
    [shadow.cljs.devtools.server.reload-classpath :as reload-classpath]
    [shadow.cljs.devtools.server.reload-npm :as reload-npm]
    [shadow.cljs.devtools.server.reload-macros :as reload-macros]
    [shadow.cljs.devtools.server.config-watch :as config-watch]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.system-bus :as system-bus]
    [shadow.build]
    [shadow.build.api :as cljs]
    [shadow.build.classpath :as build-classpath]
    [shadow.build.npm :as build-npm]
    [shadow.build.babel :as babel])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.util.concurrent Executors)))

(def app-config
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
   {:depends-on [:config]
    :start
    (fn [{:keys [compile-threads] :as config}]
      (let [n-threads
            (or compile-threads
                (.. Runtime getRuntime availableProcessors))]
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
   {:depends-on [:system-bus :executor :cache-root :http :classpath :npm :babel]
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
    :stop build-npm/stop}

   :babel
   {:depends-on []
    :start babel/start
    :stop babel/stop}

   :cljs-watch
   {:depends-on [:classpath :system-bus]
    :start (fn [classpath system-bus]
             (fs-watch-hawk/start
               (->> (build-classpath/get-classpath-entries classpath)
                    (filter #(.isDirectory %))
                    (into []))
               ;; no longer watches .clj files, reload-macros directly looks at used macros
               ["cljs" "cljc" "js"]
               #(system-bus/publish! system-bus ::sys-msg/cljs-watch {:updates %})
               ))
    :stop fs-watch-hawk/stop}})
