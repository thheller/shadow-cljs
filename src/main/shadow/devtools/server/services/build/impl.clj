(ns shadow.devtools.server.services.build.impl
  (:require [cljs.compiler :as cljs-comp]
            [clojure.core.async :as async :refer (>! <! >!! <!!)]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [clojure.tools.logging :as log]
            [shadow.devtools.server.compiler :as comp]
            [shadow.devtools.server.util :as util]
            ))

(defn- prepend [tail head]
  (into [head] tail))

(defn repl-defines
  [{:keys [proc-id build-config] :as proc-state}]
  (let [host ;; FIXME: get this from somewhere so it isn't hardcoded
        "localhost"

        port
        8200

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
  "config is a map with these options:
   :host the interface to create the websocket server on (defaults to \"localhost\")
   :port the port to listen to (defaults to random port)
   :before-load fully qualified function name to execute BEFORE reloading new files
   :after-load fully qualified function name to execute AFTER reloading ALL files

   live-reload will only load namespaces that were already required"
  [{:keys [proc-id build-config] :as proc-state}]
  (let [{:keys [console-support] :as dt-config}
        (:devtools build-config)]

    (update proc-state :compiler-state
      (fn [compiler-state]
        (-> compiler-state
            (update :closure-defines merge (repl-defines proc-state))

            (update-in [:modules (:default-module compiler-state) :entries] prepend 'shadow.devtools.client.browser)
            (cond->
              (not (false? console-support))
              (update-in [:modules (:default-module compiler-state) :entries] prepend 'shadow.devtools.client.console))
            )))))

(defn inject-node-repl
  [{:keys [proc-id build-config] :as proc-state}]
  (update proc-state :compiler-state
    (fn [compiler-state]
      (-> compiler-state
          (update :closure-defines merge (repl-defines proc-state))
          (update-in [:modules (:default-module compiler-state) :entries] prepend 'shadow.devtools.client.node)
          ))))

(defn build-msg
  [build-state e]
  (let [output (get-in build-state [:channels :output])]

    (>!! output {:type :build-message
                 :msg e})
    build-state))

(defn build-failure
  [build-state e]
  (let [output (get-in build-state [:channels :output])]

    (>!! output {:type :build-failure
                 :e e})
    build-state))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [build-config] :as build-state}]
  (try
    (let [output
          (get-in build-state [:channels :output])

          {:keys [target]}
          build-config

          compiler-state
          (-> (comp/init :dev build-config {}) ;; {:logger (util/async-logger output)}
              (repl/prepare))]

      (-> build-state
          (assoc :compiler-state compiler-state)
          (cond->
            (= :browser target)
            (inject-devtools)

            (or (= :library target)
                (= :script target))
            (inject-node-repl)
            )))

    (catch Exception e
      (build-failure build-state e))))

(defn build-compile
  [{:keys [channels compiler-state] :as build-state}]
  (let [{:keys [output]}
        channels]
    (>!! output {:type :build-start})

    (try
      (let [compiler-state
            (-> compiler-state
                (comp/compile)
                (comp/flush))]

        (>!! output {:type
                     :build-success
                     :info
                     (::comp/build-info compiler-state)})

        (assoc build-state :compiler-state compiler-state))
      (catch Exception e
        (build-failure build-state e)))))

(defmulti do-proc-control
  (fn [state msg]
    (:type msg)))

(defmethod do-proc-control :repl-state
  [state {:keys [reply-to]}]

  (if-some [repl-state (get-in state [:compiler-state :repl-state])]
    (>!! reply-to repl-state)
    (>!! reply-to ::NO-REPL))

  state)

(defmethod do-proc-control :eval-start
  [state {:keys [id eval-out client-out]}]
  (>!! eval-out {:type :repl/init
                 :repl-state (-> state :compiler-state :repl-state)})

  (update state :eval-clients assoc id {:id id
                                        :eval-out eval-out
                                        :client-out client-out}))


(defmethod do-proc-control :eval-stop
  [state {:keys [id]}]
  (update state :eval-clients dissoc id))

(defmethod do-proc-control :client-start
  [state {:keys [id in]}]
  (update state :repl-clients assoc id {:id id :in in}))

(defmethod do-proc-control :client-stop
  [state {:keys [id]}]
  (update state :repl-clients dissoc id))

(defmethod do-proc-control :start-autobuild
  [{:keys [build-config] :as state} msg]
  (if (nil? build-config)
    (build-msg state "No build configured.")
    (try
      (-> state
          (build-configure)
          (build-compile)
          (assoc :autobuild true))
      (catch Exception e
        (build-failure state e)))))

(defmethod do-proc-control :compile
  [{:keys [build-config] :as state} {:keys [reply-to] :as msg}]
  (let [result
        (if (nil? build-config)
          (build-msg state "No build configured.")
          (try
            (-> state
                (build-configure)
                (build-compile))
            (catch Exception e
              (build-failure state e))))]

    (when reply-to
      (>!! reply-to :done))

    result
    ))

(defmethod do-proc-control :configure
  [state {:keys [config] :as msg}]
  (assoc state
    :build-config config
    :autobuild false))

(defmethod do-proc-control :stop-autobuild
  [state msg]
  (assoc state :autobuild false))

(defn do-repl-result
  [{:keys [pending-results] :as state}
   {:keys [type] :as msg}]
  (case type
    :repl/result
    (let [{:keys [id value]} msg

          {:keys [reply-to] :as waiting}
          (get pending-results id)]

      (if (nil? waiting)
        (do (log/warnf "no one waiting for result: %s" (pr-str msg))
            state)
        ;; FIXME: should the reply include the msg that triggered it?
        (do (when (not (nil? value))
              (>!! reply-to value))
            (update state :pending-results dissoc id)
            )))
    state))

(defn do-repl-in
  [{:keys [compiler-state eval-clients pending-results] :as state} msg]

  (let [client-count (count eval-clients)]

    (cond
      (nil? compiler-state)
      (do (log/info "build not configured yet, how did you connect to the repl?")
          state)

      (> client-count 1)
      (do (log/info "too many clients")
          state)

      (zero? client-count)
      (do (log/info "no eval client")
          state)

      :else
      (let [{:keys [eval-out] :as eval-client}
            (first (vals eval-clients))

            {:keys [reply-to code]}
            msg

            start-idx
            (count (get-in compiler-state [:repl-state :repl-actions]))

            {:keys [repl-state] :as compiler-state}
            (try
              (if (string? code)
                (repl/process-input compiler-state code)
                (repl/process-read-result compiler-state code))
              (catch Exception e
                (log/error e "repl/process-input failed")
                compiler-state
                ))

            new-actions
            (subvec (:repl-actions repl-state) start-idx)

            pending-results
            (reduce
              (fn [pending [idx action]]
                (let [idx (+ idx start-idx)]
                  (assoc pending idx (assoc action :reply-to reply-to))))
              pending-results
              (map-indexed vector new-actions))]

        (doseq [[idx action] (map-indexed vector new-actions)
                :let [idx (+ idx start-idx)
                      action (assoc action :id idx)]]
          (>!! eval-out action))

        (assoc state
          :compiler-state compiler-state
          :pending-results pending-results)))))

(defn do-fs-updates
  [{:keys [compiler-state autobuild] :as state} modified]
  (cond-> state
    compiler-state
    (update :compiler-state cljs/reload-modified-files! modified)

    autobuild
    (build-compile)))

(defn do-config-updates [build-state config]
  build-state)


