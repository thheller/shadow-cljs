(ns shadow.devtools.server.worker.impl
  (:refer-clojure :exclude (compile))
  (:require [cljs.compiler :as cljs-comp]
            [clojure.core.async :as async :refer (go >! <! >!! <!! alt!)]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.devtools.server.compiler :as comp]
            [shadow.devtools.server.util :as util]
            ))

(defn proc? [x]
  (and (map? x) (::proc x)))

(defn worker-state? [x]
  (and (map? x) (::worker-state x)))

(defn- prepend [tail head]
  {:pre [(vector? head)]}
  (into head tail))

(defn repl-defines
  [{:keys [proc-id build-config http-config-ref] :as proc-state}]
  (let [{:keys [host port]}
        @http-config-ref

        {:keys [id]}
        build-config

        {:keys [reload-with-state before-load after-load]}
        (:devtools build-config)]

    {"shadow.devtools.client.env.enabled"
     true

     "shadow.devtools.client.env.repl_host"
     host

     "shadow.devtools.client.env.repl_port"
     port

     "shadow.devtools.client.env.build_id"
     (name id)

     "shadow.devtools.client.env.proc_id"
     (str proc-id)

     "shadow.devtools.client.env.before_load"
     (when before-load
       (str (cljs-comp/munge before-load)))

     "shadow.devtools.client.env.after_load"
     (when after-load
       (str (cljs-comp/munge after-load)))

     "shadow.devtools.client.env.reload_with_state"
     (boolean reload-with-state)
     }))

(defn inject-devtools
  [{:keys [build-config] :as proc-state}]
  (let [{:keys [console-support]}
        (:devtools build-config)]

    (update proc-state :compiler-state
      (fn [compiler-state]
        (-> compiler-state
            (update :closure-defines merge (repl-defines proc-state))

            ;; inject an entry for 'cljs.user to ensure that it is loaded as the repl eval will begin in that namespace
            (update-in [:modules (:default-module compiler-state) :entries] prepend '[cljs.user shadow.devtools.client.browser])
            (cond->
              (not (false? console-support))
              (update-in [:modules (:default-module compiler-state) :entries] prepend '[shadow.devtools.client.console]))
            )))))

(defn inject-node-repl
  [worker-state]
  (update worker-state :compiler-state
    (fn [compiler-state]
      (-> compiler-state
          (update :closure-defines merge (repl-defines worker-state))
          (update-in [:modules (:default-module compiler-state) :entries] prepend '[cljs.user shadow.devtools.client.node])
          ))))

(defn >!!output [worker-state msg]
  {:pre [(map? msg)
         (:type msg)]}

  (let [output (get-in worker-state [:channels :output])]
    (>!! output msg)
    worker-state))

(defn build-msg
  [worker-state msg]
  (>!!output worker-state
    {:type :build-message
     :msg msg}))

(defn repl-error
  [worker-state e]
  (>!!output worker-state
    {:type :repl-error
     :message (.getMessage e)
     :data (ex-data e)
     :causes
     (loop [e (.getCause e)
            causes []]
       (if e
         (recur (.getCause e) (conj causes (.getMessage e)))
         causes))}))

(defn build-failure
  [worker-state e]
  (>!!output worker-state
    {:type :build-failure
     :e e}))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [build-config] :as worker-state}]
  (try
    (let [{:keys [target]}
          build-config

          compiler-state
          (-> (cljs/init-state)
              (assoc :logger (util/async-logger (-> worker-state :channels :output)))
              (comp/init :dev build-config)
              (repl/setup))]

      (-> worker-state
          (assoc :compiler-state compiler-state)
          (cond->
            ;; defaults to browser repl unless otherwise specified
            (or (= :browser target)
                (let [rt (get-in build-config [:devtools :runtime])]
                  (or (nil? rt)
                      (= :browser rt))))
            (inject-devtools)

            (or (= :node-library target)
                (= :node-script target)
                (= :node (get-in build-config [:devtools :runtime])))
            (inject-node-repl))

          (update :compiler-state comp/process-stage :config-complete true)
          ))

    (catch Exception e
      (build-failure worker-state e))))

(defn build-compile
  [{:keys [compiler-state build-config] :as worker-state}]
  (>!!output worker-state {:type :build-start
                           :build-config build-config})

  (try
    (let [compiler-state
          (-> compiler-state
              (comp/compile)
              (comp/flush))]

      (>!!output worker-state
        {:type
         :build-complete
         :build-config
         build-config
         :info
         (::comp/build-info compiler-state)})

      (assoc worker-state :compiler-state compiler-state))
    (catch Exception e
      (build-failure worker-state e))))

(defmulti do-proc-control
  (fn [worker-state {:keys [type] :as msg}]
    type))

(defmethod do-proc-control :sync!
  [worker-state {:keys [chan] :as msg}]
  (async/close! chan)
  worker-state)

(defmethod do-proc-control :eval-start
  [worker-state {:keys [id eval-out client-out]}]
  (>!! eval-out {:type :repl/init
                 :repl-state (-> worker-state :compiler-state :repl-state)})

  (update worker-state :eval-clients assoc id {:id id
                                               :eval-out eval-out
                                               :client-out client-out}))


(defmethod do-proc-control :eval-stop
  [worker-state {:keys [id]}]
  (update worker-state :eval-clients dissoc id))

(defmethod do-proc-control :client-start
  [worker-state {:keys [id in]}]
  (update worker-state :repl-clients assoc id {:id id :in in}))

(defmethod do-proc-control :client-stop
  [worker-state {:keys [id]}]
  (update worker-state :repl-clients dissoc id))

(defmethod do-proc-control :start-autobuild
  [{:keys [build-config autobuild] :as worker-state} msg]
  (if autobuild
    worker-state ;; do nothing if already in auto mode
    (if (nil? build-config)
      (build-msg worker-state "No build configured.")
      (try
        (-> worker-state
            (build-configure)
            (build-compile)
            (assoc :autobuild true))
        (catch Exception e
          (build-failure worker-state e))))))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :compile
  [{:keys [build-config] :as worker-state} {:keys [reply-to] :as msg}]
  (let [result
        (if (nil? build-config)
          (build-msg worker-state "No build configured.")
          (try
            (-> worker-state
                (build-configure)
                (build-compile))
            (catch Exception e
              (build-failure worker-state e))))]

    (when reply-to
      (>!! reply-to :done))

    result
    ))

(defmethod do-proc-control :configure
  [worker-state {:keys [config] :as msg}]
  (assoc worker-state
    :build-config config
    :autobuild false))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :repl-eval
  [{:keys [compiler-state eval-clients pending-results] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [eval-count (count eval-clients)]

    (cond
      (nil? compiler-state)
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
              (count (get-in compiler-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as compiler-state}
              (if (string? input)
                (repl/process-input compiler-state input)
                (repl/process-read-result compiler-state input))

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
            (>!!output worker-state {:type :repl-action
                                     :action action})
            (>!! eval-out action))

          (assoc worker-state
            :compiler-state compiler-state
            :pending-results pending-results))

        (catch Exception e
          (repl-error worker-state e)
          worker-state)))))

(defmethod do-proc-control :repl-result
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

(defn do-fs-updates
  [{:keys [autobuild] :as worker-state} modified]
  (if-not autobuild
    worker-state
    (-> worker-state
        (update :compiler-state cljs/reload-modified-files! modified)
        (build-compile))))

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
                          :id client-id
                          :eval-out eval-out})

        (loop []
          (alt!
            proc-stop
            ([_] nil)

            result-chan
            ([msg]
              (when-not (nil? msg)
                (>! proc-control {:type :repl-result
                                  :result msg})
                (recur)))

            eval-out
            ([msg]
              (when-not (nil? msg)
                (>! client-out msg)
                (recur)))
            ))

        (>! proc-control {:type :eval-stop
                          :id client-id})

        (async/close! eval-out)
        (async/close! result-chan))

    result-chan))

(defn watch
  [{:keys [output-mult] :as proc} log-chan close?]
  {:pre [(proc? proc)]}
  (async/tap output-mult log-chan close?)
  proc)

(defn configure
  [{:keys [proc-control] :as proc} config]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :configure :config config})
  proc)

(defn compile
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :compile :reply-to nil})
  proc)

(defn compile!
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (let [reply-to
        (async/chan)]
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



