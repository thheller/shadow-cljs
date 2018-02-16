(ns repl
  (:require
    [shadow.cljs.devtools.server :as server]
    [shadow.cljs.devtools.api :as cljs]
    [shadow.cljs.devtools.cli]
    [shadow.debug :as dbg]
    [shadow.debug.server :as dbg-srv]))

(defn start []
  ;; (cljs/start! {:verbose true})
  ;; (cljs/start-worker :ui)

  (server/start!)
  (dbg-srv/start! {:http-root "tmp"
                   :http-port 9660})
  ;; (server/start-worker :cli)
  ;; (server/start-worker :ui)
  ;; (cljs/watch :browser {:verbose true})
  ::started)

(defn stop []
  ;; (cljs/stop!)
  (dbg-srv/stop!)
  (server/stop!)
  ::stopped)

;; (ns-tools/set-refresh-dirs "src/main")

(defn go []
  (stop)
  ;; this somehow breaks reloading
  ;; the usual :reloading message tells me that is namespace is being reloaded
  ;; but when the new instance is launched it is still using the old one
  ;; i cannot figure out why
  ;; (ns-tools/refresh :after 'repl/start)
  (start))

(defn -main []
  (start)
  (read)
  (stop))

