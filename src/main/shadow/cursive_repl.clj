(ns shadow.cursive-repl
  "this is a utility for Cursive since it can't connect to a Socket REPL natively

   launching a full JVM + Clojure for this is total overkill but its the only way I can think
   of to make Cursive connect to a remote Socket REPL."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import (java.net Socket ConnectException)))

(defn get-socket-port []
  (let [config-file (io/file "shadow-cljs.edn")]
    (if-not (.exists config-file)
      (println (format "config file not found: %s" (.getAbsolutePath config-file)))

      (let [cache-root
            (-> (slurp config-file)
                (edn/read-string)
                (get :cache-root ".shadow-cljs")
                (io/file))

            port-file
            (io/file cache-root "socket-repl.port")]

        (if-not (.exists port-file)
          (println (format "port file not found: %s" (.getAbsolutePath port-file)))

          (Long/parseLong (slurp port-file)))))))

(defn repl
  ([]
   (when-let [port (get-socket-port)]
     (repl "localhost" port)))

  ([host port]
   (try
     (let [socket
           (Socket. host port)

           socket-in
           (.getInputStream socket)

           socket-out
           (.getOutputStream socket)

           open-ref
           (volatile! true)]

       (future
         (loop []
           (let [buf (byte-array 1024)
                 read (.read socket-in buf)]
             (if (= -1 read)
               (vreset! open-ref false)
               (do (.. System/out (write buf 0 read))
                   (.. System/out (flush))
                   (recur))))))

       (let [in System/in]
         (loop []
           ;; since directly reading from in is not interruptible and would continue
           ;; when the socket-in is closed we need this available busy loop
           (when @open-ref
             (let [avl (.available in)]
               (if (zero? avl)
                 (do (Thread/sleep 10)
                     (recur))

                 (let [buf (byte-array 1024)
                       read (.read in buf)]
                   (if (= -1 read)
                     (do (vreset! open-ref false)
                         (.close socket))

                     (do (.. socket-out (write buf 0 read))
                         (.. socket-out (flush))
                         (recur)))
                   ))))))
       (println "socket closed."))
     (catch ConnectException cex
       (println "connect failed."))
     (finally
       (shutdown-agents)))))

;; clojure.main can only call main of an ns, not any fn directly
;; should be called with no args or [host port]
;; no args will use the socket repl port
(defn -main
  ([]
   (repl))
  ([cursive]
   (if (not= cursive "-r")
     (println "please call with host port args")
     (repl)))
  ([host port & dropped]
   (repl host (Long/parseLong port))))

(defn eval-top-level [& args]
  (prn args))