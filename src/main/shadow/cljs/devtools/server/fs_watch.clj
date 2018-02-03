(ns shadow.cljs.devtools.server.fs-watch
  (:require [clojure.tools.logging :as log]
            [clojure.string :as str]))

(def os-name (System/getProperty "os.name"))

(defn start [directories file-exts publish-fn]
  (let [ns-sym
        (if (str/includes? os-name "Windows")
          ;; jvm on windows supports watch fine
          'shadow.cljs.devtools.server.fs-watch-jvm
          ;; macOS doesn't have native support so it uses polling
          ;; which means 2sec delay, hawk does the native stuff
          ;; so its a lot faster but doesn't properly support delete
          'shadow.cljs.devtools.server.fs-watch-hawk)]

    (log/debugf "fs-watch using %s" ns-sym)

    (require ns-sym)

    (let [start-var (ns-resolve ns-sym 'start)]
      (-> (start-var directories file-exts publish-fn)
          (assoc ::ns ns-sym)))))

(defn stop [{::keys [ns] :as svc}]
  (let [stop-var (ns-resolve ns 'stop)]
    (stop-var svc)))
