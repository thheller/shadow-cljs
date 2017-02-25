(ns shadow.devtools.api
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
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
         :out (util/stdout-dump true)}
        (rt/init cli-app)
        (rt/start-all))))

(defn stdin-takeover!
  [worker sync-chan]
  ;; FIXME: ignoring sync-chan which is meant as a way to interrupt this loop
  ;; ie. when the node process dies, but we cannot interrupt a read off *in* anyways
  (let [get-repl-state
        #(-> worker :state-ref deref :compiler-state :repl-state)]

    (loop []
      ;; unlock stdin when we can't get repl-state, just in case
      (when-let [repl-state (get-repl-state)]

        (print (format "%s=> " (-> (get-repl-state) :current :ns)))
        (flush)

        ;; need the repl state to properly support reading ::alias/foo
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
            (when-some [result (worker/repl-eval worker ::stdin read-result)]
              (locking cljs/stdout-lock
                (case (:type result)
                  :repl/result
                  (println (:value result))

                  :repl/set-ns-complete
                  nil

                  :repl/require-complete
                  nil

                  (prn [:result result]))
                (flush))
              (recur))))))))

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

        {:keys [worker out] :as app}
        (start)]

    (try
      (-> worker
          (worker/watch out)
          (worker/configure build-config)
          (worker/start-autobuild)
          (worker/sync!)
          (stdin-takeover! sync-chan))

      (finally
        (rt/stop-all app)))
    ))

(defn repl-level [worker]
  {::r/lang :cljs
   ::r/get-current-ns
   (fn []
     (-> worker :state-ref deref :compiler-state :repl-state :current))

   ::r/completions
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

(defn node-repl*
  [{:keys [worker] :as app}
   {:keys [verbose
           node-args
           node-command
           pwd]
    :or {node-args []
         node-command "node"}}]
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
                (util/print-worker-out msg verbose)
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

  (locking cljs/stdout-lock
    (println "Node REPL shutdown. Goodbye ..."))

  :cljs/quit)

(defn node-repl
  ([]
   (node-repl {}))
  ([opts]
   (let [app (start)]
     (try
       (node-repl* app opts)
       (finally
         (rt/stop-all app))))))

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