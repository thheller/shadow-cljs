(ns main
  (:require
    [clojure.java.io :as io]
    [clojure.main :as main]
    [nrepl.server :as nrepl-srv]))

(defn repl-init []
  (in-ns 'user))

;; this is the main development server
;; that only starts the nrepl server, but loads nothing from shadow-cljs
;; lets restart everything related to shadow-cljs via the REPL
;; without killing the nrepl connection
;; could have been nrepl.cmdline, but I like a little more control

(defn -main [& args]
  (let [server (nrepl-srv/start-server :port 0 :bind "127.0.0.1")
        port-file (io/file ".nrepl-port")]
    (spit port-file (str (:port server)))
    (println "Started. nREPL ready.")
    (main/repl :init repl-init)
    (nrepl-srv/stop-server server)
    (.delete port-file)))
