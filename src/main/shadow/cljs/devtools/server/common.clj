(ns shadow.cljs.devtools.server.common
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [shadow.jvm-log :as log]
    [cognitect.transit :as transit]
    [shadow.build]
    [shadow.build.api :as cljs]
    [shadow.build.classpath :as build-classpath]
    [shadow.build.npm :as build-npm]
    [shadow.build.babel :as babel]
    [shadow.cljs.devtools.plugin-manager :as plugin-mgr])
  (:import (java.io ByteArrayOutputStream InputStream ByteArrayInputStream)
           (java.util.concurrent Executors TimeUnit)))

(defn transit-read-in [in]
  (let [r (transit/reader
            (if (string? in)
              (ByteArrayInputStream. (.getBytes in "UTF-8"))
              in)
            :json)]
    (try
      (transit/read r)
      (catch Exception e
        (throw (ex-info "failed to transit-read" {:in in} e))))))

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

   :transit-read
   {:depends-on []
    :start
    (constantly transit-read-in)
    :stop
    (fn [x])}

   :transit-str
   {:depends-on []
    :start
    (fn []
      (fn [data]
        (let [out (ByteArrayOutputStream. 4096)
              w (transit/writer out :json)]
          (try
            (transit/write w data)
            (.toString out "UTF-8")
            (catch Exception e
              (log/warn-ex e ::transit-str-failed {:data data})
              (throw e))))))

    :stop (fn [x])}

   :plugin-manager
   {:depends-on []
    :start plugin-mgr/start
    :stop plugin-mgr/stop}

   :build-executor
   {:depends-on [:config]
    :start
    (fn [{:keys [compile-threads] :as config}]
      (let [n-threads
            (or compile-threads
                (.. Runtime getRuntime availableProcessors))]
        (Executors/newFixedThreadPool n-threads)))
    :stop
    (fn [ex]
      (.shutdown ex)
      (try
        (.awaitTermination ex 10 TimeUnit/SECONDS)
        (catch InterruptedException ex)))}

   :classpath
   {:depends-on [:cache-root]
    :start (fn [cache-root]
             (-> (build-classpath/start cache-root)
                 (build-classpath/index-classpath)))
    :stop build-classpath/stop}

   :npm
   {:depends-on [:config]
    :start build-npm/start
    :stop build-npm/stop}

   :babel
   {:depends-on []
    :start babel/start
    :stop babel/stop}})

(defn get-system-config [{:keys [server-runtime plugins]}]
  (reduce
    (fn [config plugin]
      (try
        (require plugin)
        (let [plugin-var-name
              (symbol (name plugin) "plugin")

              plugin-kw
              (keyword (name plugin) "plugin")

              ;; FIXME: should eventually move this to classpath edn files and discover from there
              plugin-var
              (find-var plugin-var-name)

              {:keys [requires-server start stop] :as plugin-config}
              @plugin-var]

          (if (and requires-server (not server-runtime))
            config
            ;; wrapping the start/stop fns to they don't take down the entire system if they fail
            (let [safe-config
                  (assoc plugin-config
                    :start
                    (fn [& args]
                      (try
                        (apply start args)
                        (catch Exception e
                          (log/warn-ex e ::plugin-start-ex {:plugin plugin})
                          ::error)))
                    :stop
                    (fn [instance]
                      (when (not= ::error instance)
                        (try
                          (cond
                            (and (nil? stop) (nil? instance))
                            ::ok

                            (nil? stop)
                            (log/warn ::plugin-start-without-stop {:plugin plugin})

                            :else
                            (stop instance))

                          (catch Exception e
                            (log/warn-ex e ::plugin-stop-ex {:plugin plugin}))))))]

              (assoc config plugin-kw safe-config))))
        (catch Exception e
          (log/warn-ex e ::plugin-load-ex {:plugin plugin})
          config)))
    app-config
    plugins))
