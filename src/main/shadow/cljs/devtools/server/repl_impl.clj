(ns shadow.cljs.devtools.server.repl-impl
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.build.log :as build-log]
            [shadow.jvm-log :as log]
            [shadow.build.warnings :as warnings]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.remote.relay.api :as relay])
  (:import (java.io File)))

;; (defn repl-prompt [repl-state])
;; (defn repl-error [repl-state msg])
;; (defn repl-read-ex [repl-state ex])
;; (defn repl-result [repl-state result-as-printed-string])

(defn do-repl
  [{:keys [proc-stop] :as worker}
   relay
   input-stream
   close-signal
   {:keys [init-state
           repl-prompt
           repl-error
           repl-read-ex
           repl-result
           repl-stdout
           repl-stderr]}]

  (let [to-relay
        (async/chan 10)

        from-relay
        (relay/connect relay to-relay {})

        {:keys [client-id] :as welcome-msg}
        (<!! from-relay)

        stdin
        (async/chan)

        _
        (>!! to-relay
          {:op :hello
           :client-info {:type :repl-session
                         :build-id (:build-id worker)
                         :proc-id (:proc-id worker)}})

        read-lock
        (async/chan)

        init-ns
        (or (:ns init-state)
            (some-> worker :state-ref deref :build-config :devtools :repl-init-ns)
            'cljs.user)

        init-state
        (assoc init-state
          :ns init-ns
          :stage :read
          :client-id client-id
          :runtime-id nil)]

    ;; read loop, blocking IO
    ;; cannot block main loop or we'll never receive async events
    (async/thread
      (try
        (loop []
          ;; wait until told to read
          (when (some? (<!! read-lock))
            (let [{:keys [eof?] :as next} (repl/dummy-read-one input-stream)]
              (if eof?
                (async/close! stdin)
                ;; don't recur in case stdin was closed while in blocking read
                (when (>!! stdin next)
                  (recur))))))
        (catch Exception e
          (log/debug-ex e ::read-ex)))
      (async/close! stdin))

    (>!! read-lock 1)

    ;; initial prompt
    (repl-prompt init-state)

    (let [result
          (loop [repl-state init-state]
            (async/alt!!
              proc-stop
              ([_] ::worker-stop)

              close-signal
              ([_] ::close-signal)

              stdin
              ([read-result]
               ;; (tap> [:repl-from-stdin read-result repl-state])
               (when (some? read-result)
                 (let [{:keys [eof? error? ex source]} read-result]
                   (cond
                     eof?
                     :eof

                     error?
                     (do (repl-read-ex repl-state ex)
                         (recur repl-state))

                     (= ":repl/quit" source)
                     :quit

                     (= ":cljs/quit" source)
                     :quit

                     :else
                     (let [runtime-id
                           (or (:runtime-id repl-state)
                               ;; no previously picked runtime, pick new one from worker when available
                               (when-some [runtime-id (-> worker :state-ref deref :default-runtime-id)]
                                 (>!! to-relay {:op :runtime-print-sub
                                                :to runtime-id})
                                 (>!! to-relay {:op :request-notify
                                                :notify-op ::runtime-disconnect
                                                :query [:eq :client-id runtime-id]})
                                 runtime-id))]

                       (if-not runtime-id
                         (do (repl-error repl-state "No available JS runtime.")
                             (repl-result repl-state nil)
                             (repl-prompt repl-state)
                             (>!! read-lock 1)
                             (recur repl-state))

                         (let [msg {:op :cljs-eval
                                    :to runtime-id
                                    :input {:code source
                                            :ns (:ns repl-state)
                                            :repl true}}]

                           (>!! to-relay msg)
                           (-> repl-state
                               (assoc :stage :eval :runtime-id runtime-id :read-result read-result)
                               (recur)))))))))

              from-relay
              ([msg]
               ;; (tap> [:repl-from-relay msg repl-state])
               (when (some? msg)
                 (case (:op msg)
                   (::runtime-disconnect :client-not-found)
                   (do (repl-error repl-state "The previously used runtime disappeared. Will attempt to pick a new one when available but your state is gone.")
                       (repl-prompt repl-state)
                       ;; may be in blocking read so read-lock is full
                       ;; must not use >!! since that would deadlock
                       ;; only offer! and discard when not in blocking read anyways
                       (async/offer! read-lock 1)
                       (-> repl-state
                           (dissoc :runtime-id)
                           (recur)))

                   :eval-result-ref
                   (let [{:keys [from ref-oid eval-ns]} msg]

                     (>!! to-relay
                       {:op :obj-request
                        :to from
                        :request-op :edn
                        :oid ref-oid})

                     (-> repl-state
                         (assoc :ns eval-ns
                                :stage :print
                                :eval-result msg)
                         (recur)))

                   :obj-result
                   (let [{:keys [result]} msg]
                     (repl-result repl-state result)
                     (repl-prompt repl-state)

                     (>!! read-lock 1)
                     (-> repl-state
                         (assoc :stage :read)
                         (recur)))

                   :eval-compile-warnings
                   (let [{:keys [warnings]} msg]
                     (doseq [warning warnings]
                       (repl-error repl-state
                         (binding [warnings/*color* false]
                           (with-out-str
                             (warnings/print-short-warning (assoc warning :resource-name "<eval>"))))))
                     (repl-result repl-state nil)
                     (repl-prompt repl-state)
                     (>!! read-lock 1)
                     (recur (assoc repl-state :stage :read)))

                   :eval-compile-error
                   (let [{:keys [report]} msg]
                     (repl-error repl-state report)
                     (repl-result repl-state nil)
                     (repl-prompt repl-state)
                     (>!! read-lock 1)
                     (recur (assoc repl-state :stage :read)))

                   :eval-runtime-error
                   (let [{:keys [from ex-oid]} msg]
                     (>!! to-relay
                       {:op :obj-request
                        :to from
                        :request-op :edn
                        :oid ex-oid})
                     (recur (assoc repl-state :stage :error)))

                   :runtime-print
                   (let [{:keys [stream text]} msg]
                     (case stream
                       :stdout
                       (repl-stdout repl-state text)
                       :stderr
                       (repl-stderr repl-state text))
                     (recur repl-state))

                   (do (tap> [:unexpected-from-relay msg repl-state worker relay])
                       (recur repl-state)))
                 ))))]

      (async/close! to-relay)
      (async/close! read-lock)
      (async/close! stdin)

      result)))

(defn stdin-takeover!
  [worker {:keys [relay] :as app}]

  (do-repl
    worker
    relay
    *in*
    (async/chan)
    {:repl-prompt
     (fn repl-prompt [{:keys [ns] :as repl-state}]
       (locking build-log/stdout-lock
         (print (format "%s=> " ns))
         (flush)))

     :repl-error
     (fn repl-error [repl-state msg]
       (locking build-log/stdout-lock
         (println msg)
         (flush)))

     :repl-read-ex
     (fn repl-read-ex [repl-state ex]
       (locking build-log/stdout-lock
         (println (str "Failed to read: " ex))
         (flush)))

     :repl-result
     (fn repl-result [repl-state result-as-printed-string]
       (when result-as-printed-string
         (locking build-log/stdout-lock
           (println result-as-printed-string)
           (flush))))

     :repl-stderr
     (fn repl-stderr [repl-state text]
       (binding [*out* *err*]
         (println text)
         (flush)))

     :repl-stdout
     (fn repl-stdout [repl-state text]
       (println text)
       (flush))
     }))

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
