(ns shadow.cljs.devtools.server.worker
  (:refer-clojure :exclude (compile load-file))
  (:require [clojure.core.async :as async :refer (go thread alt!! alt! <!! <! >! >!!)]
            [shadow.jvm-log :as log]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.model :as m]
            [shadow.cljs.devtools.server.worker.impl :as impl]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [shadow.build.babel :as babel]
            [shadow.build.log :as build-log]
            [shadow.build.warnings :as warnings]
            [shadow.cljs.devtools.errors :as errors])
  (:import (java.util UUID)))

(defn compile
  "triggers an async compilation, use watch to receive notification about worker state"
  [{:keys [proc-control] :as proc}]
  {:pre [(impl/proc? proc)]}
  (>!! proc-control {:type :compile :reply-to nil})
  proc)

(defn compile!
  "triggers an async compilation and waits for the compilation result (blocking)"
  [{:keys [proc-control] :as proc}]
  {:pre [(impl/proc? proc)]}
  (let [reply-to (async/chan)]
    (>!! proc-control {:type :compile :reply-to reply-to})
    (<!! reply-to)))

(defn watch
  "watch all output produced by the worker"
  ([proc log-chan]
   (watch proc log-chan true))
  ([{:keys [output-mult] :as proc} log-chan close?]
   {:pre [(impl/proc? proc)]}
   (async/tap output-mult log-chan close?)
   proc))

(defn start-autobuild
  "automatically compile on file changes"
  [{:keys [proc-control] :as proc}]
  {:pre [(impl/proc? proc)]}
  (>!! proc-control {:type :start-autobuild})
  proc)

(defn stop-autobuild
  [{:keys [proc-control] :as proc}]
  {:pre [(impl/proc? proc)]}
  (>!! proc-control {:type :stop-autobuild})
  proc)

(defn sync!
  "ensures that all proc-control commands issued have completed"
  [{:keys [proc-control] :as proc}]
  {:pre [(impl/proc? proc)]}
  (let [chan (async/chan)]
    (>!! proc-control {:type :sync! :chan chan})
    (<!! chan))
  proc)

(defn worker-request [{:keys [proc-stop proc-control state-ref] :as worker} request]
  {:pre [(impl/proc? worker)
         (map? request)
         (keyword? (:type request))]}
  (let [result-chan
        (async/chan 1)

        repl-timeout
        (get-in @state-ref [:build-config :devtools :repl-timeout] 10000)]

    (>!! proc-control (assoc request :result-chan result-chan))

    (try
      (alt!!
        result-chan
        ([x] x)

        ;; check if the worker stopped while waiting for result
        proc-stop
        ([_]
          {:type :repl/worker-stop})

        ;; FIXME: things that actually take >10s will timeout and never show their result
        (async/timeout repl-timeout)
        ([_]
          {:type :repl/timeout}))

      (catch InterruptedException e
        {:type :repl/interrupt}))))

(defn repl-compile [worker input]
  (worker-request worker
    {:type :repl-compile
     :input input}))

(defn repl-eval [worker session-id runtime-id input]
  (worker-request worker
    {:type :repl-eval
     :session-id session-id
     :runtime-id runtime-id
     :input input}))

(defn load-file [worker {:keys [source file-path] :as file-info}]
  {:pre [(string? file-path)]}
  (worker-request worker
    {:type :load-file
     :source source
     :file-path file-path}))

(defn update-build-status [state {:keys [type] :as msg}]
  (case type
    :build-configure
    (assoc state
      :status :compiling
      :active {}
      :log [])

    :build-start
    (assoc state
      :status :compiling
      :active {}
      :log [])

    :build-complete
    (let [{:keys [sources compiled] :as info}
          (:info msg)

          warnings
          (->> (for [{:keys [warnings resource-name]} sources
                     warning warnings]
                 (assoc warning :resource-name resource-name))
               (into []))]

      (assoc state
        :status :completed
        :resources (count sources)
        :compiled (count compiled)
        :warnings warnings
        :duration
        (-> (- (or (get info :flush-complete)
                   (get info :compile-complete))
               (get info :compile-start))
            (double)
            (/ 1000))))

    ;; FIXME: how to transfer error? just the report?
    :build-failure
    (let [{:keys [report]} msg]
      (assoc state
        :status :failed
        :report report))

    :build-log
    (let [{:keys [timing-id timing] :as event} (:event msg)]
      (cond
        (not timing-id)
        (update state :log conj (build-log/event->str event))

        (= :enter timing)
        (update state :active assoc timing-id (assoc event ::m/msg (build-log/event->str event)))

        (= :exit timing)
        (-> state
            (update :active dissoc timing-id)
            (update :log conj (format "%s (%dms)"
                                (build-log/event->str event)
                                (:duration event))))

        :else
        state))

    ;; ignore all the rest for now
    ;; mostly REPL related things
    state))

(defn build-status-loop [system-bus build-id status-ref build-status-chan]
  ;; FIXME: figure out which update frequency makes sense for the UI
  ;; 10fps is probably more than enough?
  (let [flush-delay 100
        flush-fn
        (fn [state]
          (let [msg {:type :build-status
                     :build-id build-id
                     :state state}]
            (sys-bus/publish! system-bus ::m/worker-broadcast msg)
            (sys-bus/publish! system-bus [::m/worker-output build-id] msg)))]

    (go (loop [state @status-ref
               last-flush nil
               timeout (async/timeout flush-delay)]
          (reset! status-ref state)
          (alt!
            timeout
            ([_]
              ;; don't include :log for regular updates since it gets too big
              ;; FIXME: should really look into only sending incremental updates
              (let [flush-state
                    (-> state
                        (cond->
                          (not (contains? #{:failed :completed} (:status state)))
                          (dissoc :log)))]
                (when (not= flush-state last-flush)
                  (flush-fn flush-state))

                (recur state flush-state (async/timeout flush-delay))))

            build-status-chan
            ([msg]
              (if-not msg
                (flush-fn state)
                (let [after (update-build-status state msg)]
                  (recur after last-flush timeout)))))))))

;; SERVICE API

(defn start
  [config system-bus executor cache-root http classpath npm babel {:keys [build-id] :as build-config}]
  {:pre [(map? http)
         (map? build-config)
         (cp/service? classpath)
         (npm/service? npm)
         (babel/service? babel)
         (keyword? build-id)]}

  (let [proc-id
        (UUID/randomUUID) ;; FIXME: not really unique but unique enough

        _ (log/debug ::start {:build-id build-id :proc-id proc-id})

        ;; closed when the proc-stops
        ;; nothing will ever be written here
        ;; its for linking other processes to the server process
        ;; so they shut down when the worker stops
        proc-stop
        (async/chan)

        ;; controls the worker, registers new clients, etc
        proc-control
        (async/chan 10)

        ;; we put output here
        output
        (async/chan 100)

        ;; clients tap here to receive output
        output-mult
        (async/mult output)

        ;; FIXME: must use buffer, but can't use 1
        ;; when a notify happens and autobuild is running the process may be busy for a while recompiling
        ;; if another fs update happens in the meantime
        ;; and we don't have a buffer the whole config update will block
        ;; if the buffer is too small we may miss an update
        ;; ideally this would accumulate all updates into one but not sure how to go about that
        ;; (would need to track busy state of worker)
        resource-update
        (async/chan (async/sliding-buffer 10))

        asset-update
        (async/chan (async/sliding-buffer 10))

        macro-update
        (async/chan (async/sliding-buffer 10))

        ;; same deal here, 1 msg is sent per build so this may produce many messages
        config-watch
        (async/chan (async/sliding-buffer 100))

        channels
        {:proc-stop proc-stop
         :proc-control proc-control
         :output output
         :resource-update resource-update
         :macro-update macro-update
         :asset-update asset-update
         :config-watch config-watch}

        thread-state
        {::impl/worker-state true
         :http http
         :classpath classpath
         :cache-root cache-root
         :npm npm
         :babel babel
         :proc-id proc-id
         :build-id build-id
         :build-config build-config
         :autobuild false
         :runtimes {}
         :repl-sessions {}
         :pending-results {}
         :channels channels
         :system-bus system-bus
         :executor executor
         :build-state nil}

        state-ref
        (volatile! thread-state)

        thread-ref
        (util/server-thread
          (str "shadow-cljs-worker[" (name build-id) "]")
          state-ref
          thread-state
          {proc-stop nil
           proc-control impl/do-proc-control
           resource-update impl/do-resource-update
           asset-update impl/do-asset-update
           macro-update impl/do-macro-update
           config-watch impl/do-config-watch}
          {:server-id [::worker build-id]
           :idle-action impl/do-idle
           :idle-timeout 500
           :validate
           impl/worker-state?
           :validate-error
           (fn [state-before state-after msg]
             ;; FIXME: handle this better
             (prn [:invalid-worker-result-after (keys state-after) msg])
             state-before)
           :on-error
           (fn [state-before msg ex]
             ;; error already logged by server-thread fn
             state-before)
           :do-shutdown
           (fn [state]
             (>!! output {:type :worker-shutdown :proc-id proc-id})
             state)})

        {:keys [watch-dir watch-exts]
         :or {watch-exts #{"css"}}}
        (:devtools build-config)

        watch-dir
        (or watch-dir
            (get-in build-config [:devtools :http-root]))

        status-ref
        (atom {:status :pending
               :build-id build-id
               :mode :dev})

        worker-proc
        (-> {::impl/proc true
             :http http
             :proc-stop proc-stop
             :proc-id proc-id
             :proc-control proc-control
             :build-id build-id
             :system-bus system-bus
             :resource-update resource-update
             :macro-update macro-update
             :output output
             :output-mult output-mult
             :status-ref status-ref
             :thread-ref thread-ref
             :state-ref state-ref}
            (cond->
              (seq watch-dir)
              (assoc :fs-watch
                     (let [watch-dir (-> (io/file watch-dir)
                                         (.getCanonicalFile))]
                       (when-not (.exists watch-dir)
                         (io/make-parents (io/file watch-dir "dummy.html")))
                       (fs-watch/start (:fs-watch config) [watch-dir] watch-exts #(async/>!! asset-update %))))))

        build-status-chan
        (-> (async/sliding-buffer 10)
            (async/chan))]

    (async/tap output-mult build-status-chan true)

    (build-status-loop system-bus build-id status-ref build-status-chan)

    (sys-bus/sub system-bus ::m/resource-update resource-update)
    (sys-bus/sub system-bus ::m/macro-update macro-update)
    (sys-bus/sub system-bus [::m/config-watch build-id] config-watch)

    ;; ensure all channels are cleaned up properly
    (go (<! thread-ref)
        (async/close! output)
        (async/close! proc-stop)
        (async/close! proc-control)
        (async/close! resource-update)
        (async/close! macro-update)
        (async/close! asset-update)
        (log/debug ::stop {:build-id build-id :proc-id proc-id}))

    worker-proc))

(defn stop [{:keys [fs-watch] :as proc}]
  {:pre [(impl/proc? proc)]}
  (when fs-watch
    (fs-watch/stop fs-watch))
  (async/close! (:proc-stop proc))
  (<!! (:thread-ref proc)))
