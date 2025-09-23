(ns shadow.remote-nrepl
  (:require
    [nrepl.bencode :as bencode]
    [nrepl.core :as nrepl])
  (:import
    [clojure.lang RT]
    [java.io PushbackInputStream]
    [java.net Socket]))

;; wth is this private ...
(defmulti <bytes class)

(defmethod <bytes :default
  [input]
  input)

(defmethod <bytes (RT/classForName "[B")
  [#^"[B" input]
  (String. input "UTF-8"))

(defmethod <bytes clojure.lang.IPersistentVector
  [input]
  (vec (map <bytes input)))

(defmethod <bytes clojure.lang.IPersistentMap
  [input]
  (->> input
       (map (fn [[k v]] [k (<bytes v)]))
       (into {})))

(def port (Integer/parseInt (slurp ".shadow-cljs/nrepl.port")))

(comment
  (let [socket
        (Socket. "localhost" port)

        is
        (-> (.getInputStream socket)
            (PushbackInputStream.))

        os
        (.getOutputStream socket)

        read-one
        (fn read-one []
          (<bytes (bencode/read-nrepl-message is)))]

    (bencode/write-bencode os {:op "clone"})

    (let [nrepl-welcome (read-one)
          session-id (get nrepl-welcome "new-session")

          read-thread
          (Thread.
            (fn read-all []
              (loop []
                (when (try
                        (let [msg (read-one)]
                          (prn [:from-server msg])
                          true)
                        (catch Exception e
                          false))
                  (recur)))))]

      (.start read-thread)

      (prn [:nrepl-session session-id])

      (prn :init)
      (bencode/write-bencode os {:op "shadow-remote-init" :data-type "edn" :session session-id})
      (Thread/sleep 50)
      (prn :msg)
      (bencode/write-bencode os {:op "shadow-remote-msg" :session session-id :data (pr-str {:op :clj-eval :to 1 :input {:code "(+ 1 1)" :ns 'user}})}))

    (Thread/sleep 1000)
    (.close socket)
    ))

(comment
  (tap> @shadow.cljs.devtools.server.runtime/instance-ref))

(comment
  (with-open [conn (nrepl/connect :host "localhost" :port port)]
    (-> (nrepl/client conn 1000) ; message receive timeout required
        (nrepl/message {:op "shadow-remote-init" :data-type "edn"})
        (doall))))