(ns shadow.cljs.devtools.api
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.errors :as e]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.cljs.repl :as repl]
            [shadow.repl :as r]
            )
  (:import (java.io PushbackReader StringReader)))

(defn- start [opts]
  (let [config
        {}

        cli-app
        (merge
          (common/app config)
          {:worker
           {:depends-on [:system-bus :executor]
            :start worker/start
            :stop worker/stop}})]

    (-> {:config {}
         :out (util/stdout-dump true)}
        (rt/init cli-app)
        (rt/start-all))))

(defn print-result [result]
  (locking cljs/stdout-lock
    (case (:type result)
      :repl/result
      (println (:value result))

      :repl/set-ns-complete
      nil

      :repl/require-complete
      nil

      :repl/interrupt
      nil

      :repl/timeout
      (println "Timeout while waiting for REPL result.")

      :repl/no-eval-target
      (println "There is no connected JS runtime.")

      :repl/too-many-eval-clients
      (println "There are too many connected processes.")

      (prn [:result result]))
    (flush)))

(defn worker-repl-state [worker]
  (-> worker :state-ref deref :compiler-state :repl-state))

(defn worker-read-string [worker s]
  (let [rdr
        (-> s
            (StringReader.)
            (PushbackReader.))

        repl-state
        (worker-repl-state worker)]

    (repl/read-one repl-state rdr {})))

(defn repl-level [worker]
  {::r/lang :cljs
   ::r/get-current-ns
   #(:current (worker-repl-state worker))

   ::r/read-string
   #(worker-read-string worker %)
   })


(defn stdin-takeover!
  [worker]
  (loop []
    ;; unlock stdin when we can't get repl-state, just in case
    (when-let [repl-state (worker-repl-state worker)]

      (print (format "%s=> " (-> repl-state :current :ns)))
      (flush)

      ;; need the repl state to properly support reading ::alias/foo
      (let [{:keys [eof? form] :as read-result}
            (repl/read-one repl-state *in* {})]

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
            (print-result result)
            (when (not= :repl/interrupt (:type result))
              (recur))))))))

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
         :main 'shadow.cljs.devtools.client.node-repl/main
         :output-to script-name}

        out-chan
        (-> (async/sliding-buffer 10)
            (async/chan))

        _
        (go (loop []
              (when-some [msg (<! out-chan)]
                (try
                  (util/print-worker-out msg verbose)
                  (catch Exception e
                    (prn [:print-worker-out-error e])))
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

      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getInputStream node-proc) *out*))))
      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getErrorStream node-proc) *err*))))

      ;; FIXME: validate that proc started

      (r/takeover (repl-level worker)
        (let [stdin-fn
              (bound-fn []
                (stdin-takeover! worker))

              stdin-thread
              (doto (Thread. stdin-fn)
                (.start))]

          ;; async wait for the node process to exit
          ;; in case it crashes
          (async/thread
            (try
              (.waitFor node-proc)

              ;; process crashed, try to interrupt stdin block
              ;; wont' work if it is reading off *in* but we can try
              (when (.isAlive stdin-thread)
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
   (let [app (start opts)]
     (try
       (node-repl* app opts)
       (finally
         (rt/stop-all app))))))

(defn dev
  ([build]
   (dev build {:autobuild true}))
  ([build {:keys [autobuild] :as opts}]
   (let [build-config
         (config/get-build! build)

         {:keys [worker out] :as app}
         (start opts)]

     (try
       (-> worker
           (worker/watch out)
           (worker/configure build-config)
           (cond->
             autobuild
             (worker/start-autobuild)

             (not autobuild)
             (worker/compile))
           (worker/sync!)
           (stdin-takeover!))

       :done
       (catch Exception e
         (e/user-friendly-error e))

       (finally
         (rt/stop-all app))))))

(defn build-finish [{::comp/keys [build-info] :as state} config]
  (util/print-build-complete build-info config)
  state)

(defn once
  ([build]
   (once build {}))
  ([build opts]
   (let [build-config
         (config/get-build! build)]

     (try
       (util/print-build-start build-config)
       (-> (comp/init :dev build-config)
           (comp/compile)
           (comp/flush)
           (build-finish build-config))
       :done
       (catch Exception e
         (e/user-friendly-error e))
       ))))

(defn release
  ([build]
   (release build {}))
  ([build {:keys [debug source-maps pseudo-names] :as opts}]
   (let [build-config
         (config/get-build! build)]

     (try
       (util/print-build-start build-config)
       (-> (comp/init :release build-config)
           (cond->
             (or debug source-maps)
             (cljs/enable-source-maps)

             (or debug pseudo-names)
             (cljs/merge-compiler-options
               {:pretty-print true
                :pseudo-names true}))
           (comp/compile)
           (comp/optimize)
           (comp/flush)
           (build-finish build-config))
       :done
       (catch Exception e
         (e/user-friendly-error e))))))

(defn check [build]
  (let [build-config
        (config/get-build! build)]

    (try
      ;; FIXME: pretend release mode so targets don't need to account for extra mode
      ;; in most cases we want exactly :release but not sure that is true for everything?
      (-> (comp/init :release build-config)
          ;; using another dir because of source maps
          ;; not sure :release builds want to enable source maps by default
          ;; so running check on the release dir would cause a recompile which is annoying
          ;; but check errors are really useless without source maps
          (assoc :cache-dir (io/file "target" "shadow-cache" (name build) "check"))
          (cljs/enable-source-maps)
          ;; needed to get rid of process/global errors in cljs/core.cljs
          (update-in [:compiler-options :externs] conj "shadow/cljs/externs.js")
          ;; need CLJS-2019 to be useful
          (update-in [:compiler-options :closure-warnings] merge {:check-types :warning})
          (comp/compile)
          (comp/check))
      :done
      (catch Exception e
        (e/user-friendly-error e)))))

(defn test-setup []
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (cljs/merge-build-options
        {:public-dir (io/file "target" "shadow-test")
         :public-path "target/shadow-test"})
      (cljs/find-resources-in-classpath)
      ))

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