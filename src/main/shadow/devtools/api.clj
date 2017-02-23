(ns shadow.devtools.api
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.server.runtime :as rt]
            [shadow.devtools.server.config :as config]
            [shadow.devtools.server.compiler :as comp]
            [shadow.devtools.server.worker :as worker]
            [shadow.devtools.server.util :as util]
            [shadow.devtools.server.common :as common]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.cljs.repl :as repl]
            [shadow.repl :as r]
            [shadow.repl.cljs :as r-cljs])
  (:import (java.lang ProcessBuilder$Redirect)
           (java.io Writer InputStreamReader BufferedReader IOException)))

(defn- start []
  (let [cli-app
        (merge
          (common/app)
          {:worker
           {:depends-on [:fs-watch]
            :start worker/start
            :stop worker/stop}})]

    (-> {:config {}
         :default-out (util/stdout-dump)}
        (rt/init cli-app)
        (rt/start-all))))

(defn stdin-takeover!
  [worker sync-chan]
  (let [repl-in
        (async/chan 1)

        repl-result
        (worker/repl-client-connect worker ::stdin repl-in)

        state-chan
        (-> (async/sliding-buffer 1)
            (async/chan))

        ;; FIXME: how to display results properly?
        ;; should probably pipe to the output channel of worker-proc?
        _ (go (loop []
                (when-some [result (<! repl-result)]
                  (if-let [cb (r/get-feature ::r/repl-result)]
                    (cb result)
                    (println result))
                  (recur))))

        loop-result
        (loop []

          ;; unlock stdin when we can't get repl-state
          (when-let [repl-state
                     (-> worker :state-ref deref :compiler-state :repl-state)]

            (let [{:keys [eof? form] :as read-result}
                  (repl/read-one repl-state *in*)]

              (cond
                eof?
                :eof

                (nil? form)
                (recur)

                (= :repl/quit form)
                :quit

                (= :cljs/quit form)
                :quit

                :else
                (do (>!! repl-in read-result)
                    (recur))
                ))))]

    (async/close! repl-in)

    loop-result
    ))

(defn once [{:keys [build] :as args}]
  (let [build-config
        (config/get-build! build)]

    (-> (comp/init :dev build-config)
        (comp/compile)
        (comp/flush)))

  :done)

(defn dev [{:keys [build] :as args}]
  (let [build-config
        (config/get-build! build)

        sync-chan
        (async/chan 1)

        {:keys [worker default-out fs-watch] :as app}
        (start)]

    (try
      (-> worker
          (worker/watch default-out)
          (worker/configure build-config)
          (worker/start-autobuild)
          (stdin-takeover! sync-chan))
      (finally
        (rt/stop-all app)))
    ))

(defn repl-level [worker]
  {::r/lang :cljs
   ::r/get-current-ns
   (fn []
     (-> worker :state-ref deref :compiler-state :repl-state :current))

   ::r-cljs/completions
   (fn [prefix]
     ['foo])})

;; https://github.com/clojure/clojurescript/blob/master/src/main/clojure/cljs/repl/node.clj
;; I would just call that but it is private ...
(defn- pipe [^Process proc in ^Writer out]
  ;; we really do want system-default encoding here
  (with-open [^java.io.Reader in (-> in InputStreamReader. BufferedReader.)]
    (loop [buf (char-array 1024)]
      (when (.isAlive proc)
        (try
          (let [len (.read in buf)]
            (when-not (neg? len)
              (.write out buf 0 len)
              (.flush out)))
          (catch IOException e
            (when (and (.isAlive proc) (not (.contains (.getMessage e) "Stream closed")))
              (.printStackTrace e *err*))))
        (recur buf)))))

(defn node-repl
  ([]
   (node-repl {}))
  ([{:keys [node-args
            node-command
            pwd]
     :or {node-args []
          node-command "node"}}]
   (let [{:keys [worker default-out fs-watch] :as app}
         (start)]

     (try
       (let [script-name
             "target/shadow-node-repl.js"

             build-config
             {:id :node-repl
              :target :node-script
              :main 'shadow.devtools.client.node-repl/main
              :output-to script-name}

             out-chan
             (-> (async/sliding-buffer 10)
                 (async/chan))

             _
             (go (loop []
                   (when-some [msg (<! out-chan)]
                     (if-let [fn (r/get-feature ::r-cljs/compiler-info)]
                       (fn msg)
                       (util/print-worker-out msg))
                     (recur)
                     )))

             result
             (-> worker
                 (worker/watch out-chan)
                 (worker/configure build-config)
                 (worker/compile!))]

         ;; FIXME: validate that compilation succeeded

         (let [node-script
               (doto (io/file script-name)
                 ;; just to ensure it is removed, should this crash for some reason
                 (.deleteOnExit))

               node-proc
               (-> (ProcessBuilder.
                     (into-array
                       (into [node-command] node-args)))
                   (.directory
                     ;; nil defaults to JVM working dir
                     (when pwd
                       (io/file pwd)))
                   (.start))]

           (.start (Thread. (bound-fn [] (pipe node-proc (.getInputStream node-proc) *out*))))
           (.start (Thread. (bound-fn [] (pipe node-proc (.getErrorStream node-proc) *err*))))

           ;; FIXME: validate that proc started

           (r/takeover (repl-level worker)
             (let [sync-chan
                   (async/chan 1)

                   stdin-fn
                   (bound-fn []
                     (stdin-takeover! worker sync-chan))

                   stdin-thread
                   (doto (Thread. stdin-fn)
                     (.start))]

               ;; async wait for the node process to exit
               ;; in case it crashes
               (async/thread
                 (try
                   (.waitFor node-proc)

                   (async/close! sync-chan)

                   ;; process crashed, may still be reading stdin
                   (when (.isAlive stdin-thread)
                     (println "node.js process died, please type something to exit repl loop")
                     ;; this doesn't do anything when already in System.in.read ...
                     (.interrupt stdin-thread))

                   (catch Exception e
                     (prn [:node-wait-error e]))))

               ;; piping the script into node-proc instead of using command line arg
               ;; as node will otherwise adopt the path of the script as the require reference point
               ;; we want to control that via pwd
               (let [out (.getOutputStream node-proc)]
                 (io/copy (slurp node-script) out)
                 (.close out))

               (.join stdin-thread)

               ;; FIXME: more graceful shutdown of the node-proc?
               (when (.isAlive node-proc)
                 (.destroy node-proc)
                 (.waitFor node-proc))

               (when (.exists node-script)
                 (.delete node-script))))
           ))

       (finally
         (rt/stop-all app))))


   (locking cljs/stdout-lock
     (println "Node REPL shutdown. Goodbye ..."))
   :cljs/quit
    ))

(defn release [{:keys [build] :as args}]
  (let [build-config
        (config/get-build! build)]

    (-> (comp/init :release build-config)
        (comp/compile)
        (comp/flush)))
  :done)

(defn- test-setup []
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (cljs/set-build-options
        {:public-dir (io/file "target" "shadow-test")
         :public-path "target/shadow-test"})
      (cljs/find-resources-in-classpath)
      ))

(defn autotest
  "no way to interrupt this, don't run this in nREPL"
  []
  (-> (test-setup)
      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cond->
                ;; first pass, run all tests
                (empty? modified)
                (node/execute-all-tests!)
                ;; only execute tests that might have been affected by the modified files
                (not (empty? modified))
                (node/execute-affected-tests! modified))
              )))))

(defn test-all []
  (-> (test-setup)
      (node/execute-all-tests!))
  ::test-all)

(defn test-affected
  [source-names]
  {:pre [(seq source-names)
         (not (string? source-names))
         (every? string? source-names)]}
  (-> (test-setup)
      (node/execute-affected-tests! source-names))
  ::test-affected)