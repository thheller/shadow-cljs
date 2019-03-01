(ns shadow.cljs.devtools.server.fs-watch
  (:require [shadow.jvm-log :as log]
            [clojure.string :as str]))


;; hawk already uses the jvm watcher on win/linux
;; not much benefit doing this again

(def os-name (System/getProperty "os.name"))

(defn start [config directories file-exts publish-fn]
  (let [ns-sym
        (if (and (str/includes? os-name "Mac") (not (false? (:hawk config))))
          ;; macOS doesn't have native support so it uses polling
          ;; which means 2sec delay, hawk does the native stuff
          ;; so its a lot faster but doesn't properly support delete
          'shadow.cljs.devtools.server.fs-watch-hawk
          ;; jvm on windows/linux supports watch fine
          'shadow.cljs.devtools.server.fs-watch-jvm)]

    (log/debug ::fs-watch {:ns ns-sym})

    (require ns-sym)

    (let [start-var (ns-resolve ns-sym 'start)]
      (-> (start-var config directories file-exts publish-fn)
          (assoc ::ns ns-sym)))))

(defn stop [{::keys [ns] :as svc}]
  (let [stop-var (ns-resolve ns 'stop)]
    (stop-var svc)))
