(ns shadow.repl-demo
  (:require [shadow.repl :as repl]
            [clojure.pprint :refer (pprint)]
            [clojure.main :as m]
            [clojure.string :as str]
            [clojure.core.server :as srv])
  (:import java.net.ServerSocket))

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
                           {::repl/keys [level-id lang get-current-ns]}
                           (::repl/levels root-info)]
                       (pr-str [root-id level-id lang (when get-current-ns (get-current-ns))]))
                     (str/join "\n"))]

            (spit out (str report "\n")))
          (.close client))
        (recur)))
    ss
    ))

(defn repl-prompt []
  (printf "[%d:%d] %s=> " repl/*root-id* repl/*level-id* (ns-name *ns*)))

(defn repl []
  (let [latest-ns (volatile! 'user)]
    (repl/takeover
      {::repl/lang :clj
       ;; FIXME: that is just a string, should contain more info
       ::repl/get-current-ns
       (fn []
         (str @latest-ns))}

      (m/repl
        :init
        srv/repl-init

        ;; need :repl/quit support, so not just the default read
        :read
        srv/repl-read

        :prompt
        repl-prompt

        :eval
        (fn [form]
          ;; (prn [:eval form]) ;; damn you Colin for not sending what I want on Send to REPL
          (let [result (eval form)]
            (vreset! latest-ns *ns*)
            result))
        ))))

(defn remote-accept []
  (repl/enter-root
    {::repl/type :remote}
    (repl)))

(defn start-server []
  (srv/start-server
    {:name "foo"
     :port 5001
     :accept `remote-accept}))

(defn main []
  (println "Server running, :repl/quit to stop it")
  (repl/enter-root
    {::repl/type ::main}
    (repl))
  (println "Server stop ...")
  :repl/quit)

(comment


  ;; Cursive "Send to REPL" is evil and doesn't send exactly what we want
  ;; so need to type this one out
  :repl/quit

  (shadow.repl-demo/main)

  (shadow.repl-demo/repl)

  (shadow.repl/self)

  (keys (shadow.repl/self))

  (shadow.repl/root)

  (set! *print-namespace-maps* false)

  (pprint (shadow.repl/root))

  ((-> (shadow.repl/root) ::repl/levels first ::repl/get-current-ns))
  ((-> (shadow.repl/self) ::repl/get-current-ns))

  ;; now start a server to allow remote access to some data
  (def x (simple-server))
  (.close x)

  ;; run "nc localhost 5000" in terminal to query state of the repl

  ;; or a remote repl one
  (def y (start-server))
  (.close y)

  ;; run "rlwrap telnet localhost 5001" to connect to the remote repl

  ;; try with cljs-repl

  (require '[shadow.devtools.api :as api])
  (shadow.devtools.api/node-repl)

  (require 'demo.script)
  (in-ns 'demo.script)
  (require 'clojure.string)


  ;; this would be done an editor/IDE that wants to be notified should the level of a REPL change
  ;; ie. when CLJS loop is started Cursive can switch to CLJS mode
  ;; although it can always query it using the methods above

  (shadow.repl/add-watch ::foo
    (reify
      shadow.repl.protocols/ILevelCallback
      (will-enter-level [_ level]
        (prn [:level level])
        level)

      (did-exit-level [_ level]
        (prn [:level-exit level])
        level)))

  (shadow.repl/remove-watch ::foo)


  )