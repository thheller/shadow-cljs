(ns shadow.repl-demo
  (:require [shadow.repl :as repl]
            [shadow.repl.cljs :as repl-cljs]
            [clojure.main :as m]
            [clojure.string :as str]
            [clojure.core.server :as srv]
            )
  (:import java.net.ServerSocket))

(defn append-to-file [x]
  (spit "target/compiler-info.log" (str (pr-str x) "\n") :append true))

(defn simple-server
  "just the most simple demo I could think of to actually query something remotely"
  []
  (let [ss (ServerSocket. 5000)]
    (future
      (loop []
        (let [client (.accept ss)]
          (let [out (.getOutputStream client)

                report
                (->> (for [[root-id root-info] (repl/roots)

                           :let [{::repl/keys [levels]} root-info]

                           {::repl/keys [level-id get-current-ns]}
                           levels]
                       (pr-str [root-id level-id (when get-current-ns (get-current-ns))]))
                     (str/join "\n"))]
            (spit out (str report "\n")))
          (.close client))
        (recur)))
    ss
    ))

(defn provide! []
  (let [repl-features
        {::repl-cljs/compiler-info
         append-to-file

         ::repl/level-enter
         (fn [level]
           (prn ::level-enter))

         ::repl/level-exit
         (fn [level]
           (prn ::level-exit))}]

    (repl/provide! repl-features)
    ))

(defn repl []
  (repl/takeover
    ;; FIXME: doesn't work, not sure why
    {::repl/get-current-ns
     (fn [] (str *ns*))}

    (m/repl
      :init srv/repl-init
      ;; need :repl/quit support
      :read srv/repl-read)))

(defn remote-accept []
  (repl/enter-root {}
    (repl)))

(defn socket-repl []
  (srv/start-server
    {:name "foo"
     :port 5001
     :accept `remote-accept}))

(defn main []
  (provide!)
  (with-open [simple-srv
              (simple-server)

              repl-srv
              (socket-repl)]

    ;; this probably wouldn't use a repl, just to make interacting with it easier
    (println "Server running, :repl/quit to stop it")
    (repl)
    (println "Server stop ...")
    :repl/quit))

(comment

  (require '[shadow.devtools.api :as api])

  ;; first start a blank node repl
  ;; very verbose and noisy
  ;; no way to differentiate compiler output from repl output
  (shadow.devtools.api/node-repl)

  (shadow.repl/clear!)
  (shadow.repl-demo/provide!)

  ;; resume normal repl
  ;; Cursive "Send to REPL" is evil and doesn't send exactly what we want
  ;; so need to type these out
  :repl/quit

  ;; now start a server to allow remote access to some data
  (def x (simple-server))
  (.close x)

  (shadow.repl-demo/main)

  ;; and lets try that again
  ;; much less verbose, output goes to different places
  (shadow.devtools.api/node-repl)

  ;; run "nc localhost 5000" in terminal to query state of the repl

  (let [fn (get (shadow.repl/current-level) :shadow.repl.cljs/get-current-ns)] (fn))

  (require 'demo.script)

  (in-ns 'demo.script)

  ;; query state again

  (require 'goog.net.XhrIo)

  ;; query state again

  )