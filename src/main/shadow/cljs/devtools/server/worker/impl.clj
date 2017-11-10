(ns shadow.cljs.devtools.server.worker.impl
  (:refer-clojure :exclude (compile))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.async :as async :refer (go >! <! >!! <!! alt!)]
            [clojure.tools.logging :as log]
            [cljs.compiler :as cljs-comp]
            [shadow.build.api :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.build :as build]
            [shadow.build.api :as build-api]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.config :as config]))

(defn proc? [x]
  (and (map? x) (::proc x)))

(defn worker-state? [x]
  (and (map? x) (::worker-state x)))

(defn repl-sources-as-client-resources
  "transforms a seq of resource-ids to return more info about the resource
   a REPL client needs to know more since resource-ids are not available at runtime"
  [source-ids state]
  (->> source-ids
       (map (fn [src-id]
              (let [src (get-in state [:sources src-id])]
                (select-keys src [:resource-id :type :resource-name :output-name :from-jar]))))
       (into [])))

(defmulti transform-repl-action
  (fn [state action]
    (:type action))
  :default ::default)

(defmethod transform-repl-action ::default [state action]
  action)

(defmethod transform-repl-action :repl/require [state action]
  (update action :sources repl-sources-as-client-resources state))

(defn >!!output [{:keys [system-bus] :as worker-state} msg]
  {:pre [(map? msg)
         (:type msg)]}

  (let [output (get-in worker-state [:channels :output])]
    (>!! output msg)
    worker-state))

(defn update-status!
  [{:keys [system-bus status-ref build-config] :as worker-state} status-id status-info]
  (let [status
        {:build-id (:build-id build-config)
         :status status-id
         :info status-info}]
    (vreset! status-ref status)
    ;; yuck, can't properly alias
    (sys-bus/publish! system-bus :shadow.cljs.devtools.server.worker/update status))

  worker-state)

(defn build-msg
  [worker-state msg]
  (>!!output worker-state
    {:type :build-message
     :msg msg}))

(defn repl-error [e]
  (log/debug repl-error e)
  {:type :repl/error
   :ex e})

(defn build-failure
  [{:keys [build-config] :as worker-state} e]
  (update-status! worker-state :error {:error e})

  (>!!output worker-state
    {:type :build-failure
     :build-config build-config
     :e e}))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [build-config proc-id http executor npm classpath cache-root] :as worker-state}]

  (>!!output worker-state {:type :build-configure
                           :build-config build-config})

  (update-status! worker-state :configure {})

  (try
    ;; FIXME: allow the target-fn read-only access to worker-state? not just worker-info?
    ;; it may want to put things on the websocket?
    (let [worker-info
          {:proc-id proc-id
           :host (:host http)
           :port (:port http)
           :ssl (:ssl http)}

          build-id
          (:build-id build-config)

          build-state
          (-> (build-api/init)
              (build-api/with-classpath classpath)
              (build-api/with-npm npm)
              (build-api/with-executor executor)
              (build-api/with-logger (util/async-logger (-> worker-state :channels :output)))
              (build-api/with-cache-dir (config/make-cache-dir cache-root build-id :dev))
              (assoc
                :worker-info worker-info
                :mode :dev
                ::compile-attempt 0)
              (build/configure :dev build-config))]

      (update-status! worker-state :configured {})

      ;; FIXME: should maybe cleanup old :build-state if there is one (re-configure)
      (assoc worker-state :build-state build-state))
    (catch Exception e
      (-> worker-state
          (dissoc :build-state) ;; just in case there is an old one
          (build-failure e)))))

(defn build-compile
  [{:keys [build-state build-config] :as worker-state}]
  (>!!output worker-state {:type :build-start
                           :build-config build-config})

  ;; this may be nil if configure failed, just silently do nothing for now
  (if (nil? build-state)
    worker-state
    (try
      (update-status! worker-state :started {})

      (let [{::build/keys [build-info] :keys [build-sources] :as build-state}
            (-> build-state
                (build/compile)
                (build/flush)
                (update ::compile-attempt inc))]

        (>!!output worker-state
          {:type
           :build-complete
           :build-config
           build-config
           :info
           build-info})

        (update-status! worker-state :complete {:build-info build-info})

        (assoc worker-state
               :build-state build-state
               :last-build-sources build-sources))
      (catch Exception e
        (build-failure worker-state e)))))

(defmulti do-proc-control
  (fn [worker-state {:keys [type] :as msg}]
    type))

(defmethod do-proc-control :sync!
  [worker-state {:keys [chan] :as msg}]
  (async/close! chan)
  worker-state)

(defmethod do-proc-control :eval-start
  [{:keys [build-state] :as worker-state} {:keys [client-id eval-out client-out]}]
  (>!! eval-out {:type :repl/init
                 :repl-state
                 (-> (:repl-state build-state)
                     (update :repl-sources repl-sources-as-client-resources build-state))})

  (>!!output worker-state {:type :repl/eval-start :client-id client-id})
  (update worker-state :eval-clients assoc client-id {:client-id client-id
                                                      :eval-out eval-out
                                                      :client-out client-out}))


(defmethod do-proc-control :eval-stop
  [worker-state {:keys [client-id]}]
  (>!!output worker-state {:type :repl/eval-stop :id client-id})
  (update worker-state :eval-clients dissoc client-id))

(defmethod do-proc-control :client-start
  [worker-state {:keys [id in]}]
  (>!!output worker-state {:type :repl/client-start :id id})
  (update worker-state :repl-clients assoc id {:id id :in in}))

(defmethod do-proc-control :client-stop
  [worker-state {:keys [id]}]
  (>!!output worker-state {:type :repl/client-stop :id id})
  (update worker-state :repl-clients dissoc id))

(defmethod do-proc-control :start-autobuild
  [{:keys [build-config autobuild] :as worker-state} msg]
  (if autobuild
    ;; do nothing if already in auto mode
    worker-state
    ;; compile immediately, autobuild is then checked later
    (-> worker-state
        (assoc :autobuild true)
        (build-configure)
        (build-compile)
        )))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :compile
  [worker-state {:keys [reply-to] :as msg}]
  (let [result
        (-> worker-state
            (build-configure)
            (build-compile))]

    (when reply-to
      (>!! reply-to :done))

    result
    ))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :load-file
  [{:keys [build-state eval-clients pending-results] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [eval-count (count eval-clients)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (> eval-count 1)
      (do (>!! result-chan {:type :repl/too-many-eval-clients :count eval-count})
          worker-state)

      (zero? eval-count)
      (do (>!! result-chan {:type :repl/no-eval-target})
          worker-state)

      :else
      (try
        (let [{:keys [eval-out] :as eval-client}
              (first (vals eval-clients))

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (repl/repl-load-file* build-state msg)

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              pending-results
              (reduce
                (fn [pending [idx action]]
                  (let [idx (+ idx start-idx)]
                    (assoc pending idx (assoc action :reply-to result-chan))))
                pending-results
                (map-indexed vector new-actions))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            (>!!output worker-state {:type :repl/action
                                     :action action})
            (>!! eval-out (transform-repl-action build-state action)))

          (assoc worker-state
                 :build-state build-state
                 :pending-results pending-results))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defmethod do-proc-control :repl-eval
  [{:keys [build-state eval-clients pending-results] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [eval-count (count eval-clients)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (> eval-count 1)
      (do (>!! result-chan {:type :repl/too-many-eval-clients :count eval-count})
          worker-state)

      (zero? eval-count)
      (do (>!! result-chan {:type :repl/no-eval-target})
          worker-state)

      :else
      (try
        (let [{:keys [eval-out] :as eval-client}
              (first (vals eval-clients))

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (if (string? input)
                (repl/process-input build-state input)
                (repl/process-read-result build-state input))

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              pending-results
              (reduce
                (fn [pending [idx action]]
                  (let [idx (+ idx start-idx)]
                    (assoc pending idx (assoc action :reply-to result-chan))))
                pending-results
                (map-indexed vector new-actions))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            (>!!output worker-state {:type :repl/action
                                     :action action})
            (>!! eval-out (transform-repl-action build-state action)))

          (assoc worker-state
            :build-state build-state
            :pending-results pending-results))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defmethod do-proc-control :repl/result
  [{:keys [pending-results] :as worker-state}
   {:keys [result] :as msg}]

  ;; forward everything to out as well
  (>!!output worker-state msg)

  (let [{:keys [id]}
        result

        {:keys [reply-to] :as waiting}
        (get pending-results id)]

    (if (nil? waiting)
      worker-state

      ;; FIXME: should the reply include the msg that triggered it?
      (do (>!! reply-to result)
          (update worker-state :pending-results dissoc id))
      )))

(defn do-macro-update
  [{:keys [build-state autobuild] :as worker-state} {:keys [macro-namespaces] :as msg}]
  (cond
    (not build-state)
    worker-state

    ;; the updated macro may not be used by this build
    ;; so we can skip the rebuild
    (build-api/build-affected-by-macros? build-state macro-namespaces)
    (-> worker-state
        (update :build-state build-api/reset-resources-using-macros macro-namespaces)
        (cond->
          autobuild
          (build-compile)))

    :do-nothing
    worker-state))

(defn do-resource-update
  [{:keys [autobuild build-state] :as worker-state}
   {:keys [resources] :as msg}]

  (let [sources-in-use
        (into #{} (:last-build-sources worker-state))

        sources-used-by-build
        (->> resources
             ;; cannot check against :sources since they will be removed by reset
             ;; :build-sources maintains is not affected
             (filter #(contains? sources-in-use %))
             (into []))]

    (cond
      ;; configuration errors mean to build state
      (not build-state)
      worker-state

      ;; always recompile if the first compile attempt failed
      ;; since we don't know which files it wanted to use
      ;; after that only recompile when files in use by the build changed
      (and (pos? (::compile-attempt build-state))
           (not (seq sources-used-by-build)))
      worker-state

      :else
      (let [build-state
            (build-api/reset-resources build-state sources-used-by-build)]
        (log/debugf "build-update: %s" sources-used-by-build)

        (-> worker-state
            (assoc :build-state build-state)
            (cond->
              autobuild
              (build-compile))))
      )))

(defn do-asset-update
  [{:keys [eval-clients] :as worker-state} updates]
  (let [watch-path
        (get-in worker-state [:build-config :devtools :watch-path])

        updates
        (->> updates
             (filter #(= :mod (:event %)))
             (map #(str watch-path "/" (:name %)))
             (into []))]
    ;; only interested in file modifications
    ;; don't need file instances, just the names
    (when (seq updates)
      (doseq [{:keys [eval-out] :as client} (vals eval-clients)]
        (>!! eval-out {:type :asset-watch
                       :updates updates}))))

  worker-state)

(defn do-config-watch
  [{:keys [autobuild] :as worker-state} {:keys [config] :as msg}]
  (-> worker-state
      (assoc :build-config config)
      (cond->
        autobuild
        (-> (build-configure)
            (build-compile)))))

(defn repl-eval-connect
  [{:keys [proc-stop proc-control] :as proc} client-id client-out]
  {:pre [(proc? proc)]}
  ;; result-chan
  ;; creating a new chan here instead of just handing out repl-result
  ;; closing it is currently the only way to a eval-client can signal a disconnect
  ;; we will however just pipe messages through as we have nothing useful to do with them

  ;; eval-out
  ;; FIXME: just piping through but could just talk to client-out directly?
  (let [result-chan
        (async/chan)

        eval-out
        (async/chan)]

    (go (>! proc-control {:type :eval-start
                          :client-id client-id
                          :eval-out eval-out})

        (loop []
          (alt!
            proc-stop
            ([_] nil)

            result-chan
            ([msg]
              (when-not (nil? msg)
                (>! proc-control {:type :repl/result
                                  :result msg})
                (recur)))

            eval-out
            ([msg]
              (when-not (nil? msg)
                (>! client-out msg)
                (recur)))
            ))

        (>! proc-control {:type :eval-stop
                          :client-id client-id})

        (async/close! eval-out)
        (async/close! result-chan))

    result-chan))

;; FIXME: remove these ... just make worker do the stuff directly, this is nonsense

(defn watch
  [{:keys [output-mult] :as proc} log-chan close?]
  {:pre [(proc? proc)]}
  (async/tap output-mult log-chan close?)
  proc)

(defn compile
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :compile :reply-to nil})
  proc)

(defn compile!
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (let [reply-to (async/chan)]
    (>!! proc-control {:type :compile :reply-to reply-to})
    (<!! reply-to)))

(defn start-autobuild
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :start-autobuild})
  proc)

(defn stop-autobuild
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :stop-autobuild})
  proc)

(defn sync! [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (let [chan (async/chan)]
    (>!! proc-control {:type :sync! :chan chan})
    (<!! chan))
  proc)


