(ns shadow.nrepl-debug
  (:require [nrepl.transport :as transport]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io])
  (:import [java.net ServerSocket Socket]
           [java.io FileOutputStream OutputStreamWriter]))

(defonce print-lock (Object.))

;; using an agent so log writes don't delay messages
(defonce write-agent (agent {}))

(defn log [agent-state out from msg]
  (let [text (with-out-str (pprint msg))]
    (prn [:msg from (count text)])
    (locking print-lock
      (binding [*out* out]
        (println ";; ----------------------------------------")
        (println ";; -- FROM " from)
        (println ";; ----------------------------------------")
        (println text)
        (flush))))
  agent-state)

(defn client-loop [id client to-port]
  (let [target (Socket. "localhost" to-port)

        target-transport
        (transport/bencode target)

        client-transport
        (transport/bencode client)

        log-file
        (io/file "target" "nrepl-debug" (str "nrepl-" id ".log.edn"))]

    (io/make-parents log-file)

    (with-open [log-out (-> log-file (FileOutputStream.) (OutputStreamWriter.))]

      ;; target -> client
      (doto (Thread.
              #(do (try
                     (loop []
                       (when-some [target-msg (transport/recv target-transport)]
                         (send write-agent log log-out :target target-msg)
                         (transport/send client-transport target-msg)
                         (recur)))
                     (catch Exception e
                       (prn [:target-ex e])))
                   (.close client)))
        (.start))

      (try
        ;; client -> target
        (loop []
          (when-some [client-msg (transport/recv client-transport)]
            (send write-agent log log-out :client client-msg)
            (transport/send target-transport client-msg)
            (recur)))
        (catch Exception e
          (prn [:client-ex e]))))

    (.close target)
    (.close client)))

(defn -main [from to]
  (let [from-port (Long/parseLong from)
        to-port (Long/parseLong to)

        server-socket
        (ServerSocket. from-port)]

    (prn [:server-ready from-port to-port])

    (loop [id 0]
      (let [client (.accept server-socket)]
        (prn [:client-accepted client])
        (client-loop id client to-port)
        (recur (inc id))))))
