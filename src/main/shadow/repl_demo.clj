(ns shadow.repl-demo
  (:require [shadow.repl :as repl]
            [shadow.repl.cljs :as repl-cljs]
            [shadow.devtools.api :as api]
            [clojure.string :as str]
            [clojure.core.server :as srv]
            [clojure.main :as m])
  (:import java.net.ServerSocket))

(defn append-to-file [x]
  (spit "target/compiler-info.log" (str (pr-str x) "\n") :append true))

(defn level-server
  "just the most simple demo I could think of to actually query something remotely"
  []
  (let [ss (ServerSocket. 5000)]
    (future
      (loop []
        (let [client (.accept ss)]
          (let [out (.getOutputStream client)

                report
                (->> (for [{::repl/keys [level-id]
                            ::repl-cljs/keys [get-current-ns]}
                           (repl/levels)]
                       (pr-str [level-id (get-current-ns)]))
                     (str/join "\n"))]
            (spit out (str report "\n")))
          (.close client))
        (recur)))
    ss
    ))

(defn repl-print [obj]
  (prn [:result obj]))

(defn repl
  "the IDE/tool in question would provide this and call this as a main
   not
   java -cp ... clojure.main -r
   but
   java -cp ... shadow.repl-demo"
  []
  (with-open [x (level-server)]
    (let [repl-features
          {::repl-cljs/compiler-info
           append-to-file

           ::repl/level-enter
           (fn [level]
             (prn ::level-enter))

           ::repl/level-exit
           (fn [level]
             (prn ::level-exit))}

          result
          (repl/with-features repl-features
            ;; using srv/repl-read since we need :repl/quit
            (m/repl
              :need-prompt (constantly false)
              ;; :print repl-print
              :init srv/repl-init
              :read srv/repl-read
              ))]

      (println "Goodbye ...")
      result)))

(defn main []
  (repl))


(comment





  ;; first start a blank node repl
  ;; very verbose and noisy
  ;; no way to differentiate compiler output from repl output
  (shadow.devtools.api/node-repl)

  ;; resume normal repl
  ;; Cursive "Send to REPL" is evil and doesn't send exactly what we want
  ;; so need to type these out
  :repl/quit

  ;; now enter the demo repl
  (shadow.repl-demo/repl)


  ;; and lets try that again
  ;; much less verbose, output goes to different places
  (shadow.devtools.api/node-repl)

  ;; run "nc localhost 5000" in terminal to query state of the repl



  (require 'demo.script)

  (in-ns 'demo.script)

  ;; query state again

  (require 'goog.net.XhrIo)

  ;; query state again
































  )