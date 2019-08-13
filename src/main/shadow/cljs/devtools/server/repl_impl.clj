(ns shadow.cljs.devtools.server.repl-impl
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.build.api :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.repl :as r]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.build.log :as build-log]
            [shadow.jvm-log :as log]
            [shadow.build.warnings :as warnings])
  (:import (java.io StringReader PushbackReader File)
           [java.util UUID]))

(defn print-result [result]
  (locking build-log/stdout-lock
    (case (:type result)
      :repl/result
      (let [{:keys [error value]} result]
        (if-not (some? error)
          (println value)
          ;; FIXME: let worker format the error and source map
          ;; worker has full access to build info, this here doesn't
          (let [{:keys [ex-data error stack]} result]
            (println "== JS EXCEPTION ==============================")
            (if (seq stack)
              (println stack)
              (println error))
            (when (seq ex-data)
              (println "Error Data:")
              (prn ex-data))
            (println "==============================================")
            )))

      (:repl/require-error :repl/invoke-error)
      (println (or (:stack result)
                   (:error result)
                   (:message result)))

      :repl/set-ns-complete
      (println "nil")

      :repl/require-complete
      (println "nil")

      :repl/interrupt
      nil

      :repl/timeout
      (println "Timeout while waiting for REPL result.")

      :repl/no-runtime-connected
      (println "No application has connected to the REPL server. Make sure your JS environment has loaded your compiled ClojureScript code.")

      :repl/too-many-runtimes
      (println "There are too many connected processes.")

      :repl/worker-stop
      (println "The REPL worker has stopped.")

      (prn [:result result]))
    (flush)))

(defn handle-repl-result [worker actions]
  (doseq [{:keys [warnings result] :as action} actions]
    (doseq [warning warnings]
      (warnings/print-short-warning warning))
    (print-result result)))

(defn worker-build-state [worker]
  (-> worker :state-ref deref :build-state))

(defn worker-read-string [worker s]
  (let [rdr
        (-> s
            (StringReader.)
            (PushbackReader.))

        build-state
        (worker-build-state worker)]

    (repl/read-one build-state rdr {})))

(defn repl-level [worker]
  {::r/lang :cljs
   ::r/get-current-ns
   #(get-in (worker-build-state worker) [:repl-state :current-ns])

   ::r/read-string
   #(worker-read-string worker %)
   })

(def repl-api-fns
  ;; return value of these is ignored, print to *out* should work?
  {})

(defn do-repl-api-fn [app worker repl-state {:keys [form] :as read-result}]
  (let [[special-fn & args]
        form

        handler
        (get repl-api-fns special-fn)]

    (apply handler app worker repl-state read-result args)))

(defn repl-print-chan []
  (let [print-chan
        (async/chan)]

    (go (loop []
          (when-some [msg (<! print-chan)]
            (case (:type msg)
              :repl/out
              (locking build-log/stdout-lock
                (println (:text msg))
                (flush))

              :repl/err
              (locking build-log/stdout-lock
                (binding [*out* *err*]
                  (println (:text msg))
                  (flush)))

              :ignored)
            (recur)
            )))

    print-chan
    ))

(defn stdin-takeover!
  [worker {:keys [out] :as app} runtime-id]
  (let [print-chan
        (repl-print-chan)

        session-id
        (str (UUID/randomUUID))]

    (worker/watch worker print-chan true)

    (r/takeover (repl-level worker)
      (loop []
        ;; unlock stdin when we can't get repl-state, just in case
        (when-let [build-state (worker-build-state worker)]

          ;; FIXME: inf-clojure fails when there is a space between \n and =>
          (print (format "[%d:%d]~%s=> " r/*root-id* r/*level-id* (-> build-state :repl-state :current-ns)))
          (flush)

          ;; need the repl state to properly support reading ::alias/foo
          (let [{:keys [eof? error? ex form] :as read-result}
                (repl/read-one build-state *in* {})]

            (log/debug ::read-result read-result)

            (cond
              eof?
              :eof

              error?
              (do (println (str "Failed to read: " ex))
                  (recur))

              (nil? form)
              (recur)

              (= :repl/quit form)
              :quit

              (= :cljs/quit form)
              :quit

              (and (list? form)
                   (contains? repl-api-fns (first form)))
              (do (do-repl-api-fn app worker build-state read-result)
                  (recur))

              :else
              (when-some [actions (worker/repl-eval worker session-id runtime-id read-result)]
                (handle-repl-result worker actions)
                (when-not (some #{:repl/interrupt :repl/worker-stop} (map #(get-in % [:result type]) actions))
                  (recur))))))))
    (async/close! print-chan)

    nil
    ))

(defn node-repl*
  [{:keys [supervisor config] :as app}
   {:keys [via
           verbose
           node-args
           node-command
           pwd]
    :or {node-args []
         node-command "node"}
    :as opts}]
  (let [script-name
        (str (:cache-root config) File/separatorChar "shadow-node-repl.js")

        build-config
        {:build-id :node-repl
         :target :node-script
         :main 'shadow.cljs.devtools.client.node-repl/main
         :hashbang false
         :output-to script-name}

        {:keys [proc-stop] :as worker}
        (super/start-worker supervisor build-config opts)

        result
        (worker/compile! worker)]

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

      ;; FIXME: validate that proc started properly

      ;; FIXME: these should print to worker out instead
      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getInputStream node-proc) *out*))))
      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getErrorStream node-proc) *err*))))

      ;; piping the script into node-proc instead of using command line arg
      ;; as node will otherwise adopt the path of the script as the require reference point
      ;; we want to control that via pwd

      (let [out (.getOutputStream node-proc)]
        (io/copy (slurp node-script) out)
        (.close out))

      ;; worker stop should kill the node process
      (go (<! proc-stop)
          (try
            (when (.isAlive node-proc)
              (.destroyForcibly node-proc))
            (catch Exception e)))

      ;; node process might crash which should stop the worker
      (async/thread
        (try
          (let [code (.waitFor node-proc)]
            (log/info ::node-repl-exit {:code code}))
          (finally
            (super/stop-worker supervisor :node-repl)
            )))

      (assoc worker
        :node-script node-script
        :node-proc node-proc))))
