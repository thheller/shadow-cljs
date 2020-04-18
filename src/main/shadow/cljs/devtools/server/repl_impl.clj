(ns shadow.cljs.devtools.server.repl-impl
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.build.api :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.build.log :as build-log]
            [shadow.jvm-log :as log]
            [shadow.build.warnings :as warnings]
            [shadow.cljs.devtools.errors :as errors])
  (:import (java.io StringReader PushbackReader File)
           [java.util UUID]))

(defn handle-repl-action-result [{:keys [warnings result] :as action}]
  (doseq [warning warnings]
    (warnings/print-short-warning warning))

  ;; don't forward results to internal actions to clients
  ;; ns results in require, eval, set-ns but the client doesn't need to know that
  (when-not (:internal action)
    (case (:type result)
      (:repl/require-error :repl/invoke-error)
      (println (or (:stack result)
                   (:error result)
                   (:message result)))

      :repl/set-ns-complete
      (println "nil")

      :repl/require-complete
      (println "nil")

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
            ))))))

(defn handle-repl-result [worker result]
  (locking build-log/stdout-lock
    (case (:type result)
      :repl/results
      (doseq [action (:results result)]
        (handle-repl-action-result action))

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

      :repl/error
      (errors/error-format *out* (:ex result))

      (prn [:result result]))
    (flush)))

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

    (loop []
      ;; unlock stdin when we can't get repl-state, just in case
      (when-let [build-state (worker-build-state worker)]

        ;; FIXME: inf-clojure fails when there is a space between \n and =>
        (print (format "%s=> " (-> build-state :repl-state :current-ns)))
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

            :else
            (when-some [result (worker/repl-eval worker session-id runtime-id read-result)]
              (handle-repl-result worker result)
              (when-not (contains? #{:repl/interrupt :repl/worker-stop} (:type result))
                (recur)))))))
    (async/close! print-chan)

    nil
    ))

(defn node-repl*
  [{:keys [supervisor config] :as app}
   {:keys [via
           verbose
           build-id
           node-args
           node-command
           pwd]
    :or {node-args []
         build-id :node-repl
         node-command "node"}
    :as opts}]
  (let [script-name
        (str (:cache-root config) File/separatorChar "shadow-node-repl.js")

        build-config
        {:build-id build-id
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
            (super/stop-worker supervisor build-id)
            )))

      (assoc worker
        :node-script node-script
        :node-proc node-proc))))
