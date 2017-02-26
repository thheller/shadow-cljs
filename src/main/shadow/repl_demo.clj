(ns shadow.repl-demo
  (:require [shadow.repl :as repl]
            [clojure.pprint :refer (pprint)]
            [clojure.main :as m]
            [clojure.string :as str]
            [clojure.core.server :as srv])
  (:import java.net.ServerSocket
           (java.io StringReader PushbackReader)))

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

(defn repl
  ([]
   (repl {}))
  ([{:keys [debug] :as opts}]
   (let [latest-ns (volatile! 'user)

         {::repl/keys [print] :as root}
         (repl/root)]
     (repl/takeover
       {::repl/lang :clj
        ::repl/get-current-ns
        (fn []
          ;; FIXME: dont return just the string, should contain more info
          (str @latest-ns))

        ::repl/read-string
        (fn [s]
          (binding [*ns* @latest-ns]
            (try
              (let [eof
                    (Object.)

                    result
                    (read
                      {:read-cond true
                       :features #{:clj}
                       :eof eof}
                      (PushbackReader. (StringReader. s)))]
                (if (identical? eof result)
                  {:result eof}
                  {:result :success :value result}))
              (catch Exception e
                {:result :exception
                 :e e}
                ))))}

       (m/repl
         :init
         srv/repl-init

         ;; need :repl/quit support, so not just the default read
         :read
         (if debug
           (fn [request-prompt request-exit]
             (let [result (srv/repl-read request-prompt request-exit)]
               (prn [:repl-read result])
               result
               ))
           srv/repl-read)

         :print
         (fn [x]
           (if print
             (print x)
             (prn x)))

         :prompt
         repl-prompt

         :eval
         (fn [form]
           ;; (prn [:eval form]) ;; damn you Colin for not sending what I want on Send to REPL
           (let [result (eval form)]
             (vreset! latest-ns *ns*)
             result))
         )))))

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
  (println "REPL ready, type :repl/quit to exit")
  (repl/enter-root
    {::repl/type ::main}
    (repl))
  (println "REPL stop. Goodbye ...")
  :repl/quit)

(comment


  ;; Cursive "Send to REPL" is evil and doesn't send exactly what we want
  ;; so need to type this one out
  :repl/quit

  (shadow.repl-demo/main)

  (shadow.repl-demo/repl)

  (shadow.repl-demo/repl {:debug true})

  (pprint (shadow.repl/self))

  (keys (shadow.repl/self))

  (pprint (shadow.repl/roots))

  (shadow.repl/root)

  (set! *print-namespace-maps* false)

  (pprint (shadow.repl/root))

  ((-> (shadow.repl/root) ::repl/levels first ::repl/get-current-ns))
  ((-> (shadow.repl/self) ::repl/get-current-ns))

  (let [read-string (:shadow.repl/read-string (shadow.repl/level 7 1))]
    (read-string "foo"))

  ;; now start a server to allow remote access to some data
  (def x (shadow.repl-demo/simple-server))
  (.close x)

  ;; run "nc localhost 5000" in terminal to query state of the repl

  ;; or a remote repl one
  (def y (shadow.repl-demo/start-server))
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