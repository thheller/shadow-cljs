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

(defn inject-devtools
  "config is a map with these options:
   :host the interface to create the websocket server on (defaults to \"localhost\")
   :port the port to listen to (defaults to random port)
   :before-load fully qualified function name to execute BEFORE reloading new files
   :after-load fully qualified function name to execute AFTER reloading ALL files

   live-reload will only load namespaces that were already required"
  [{:keys [proc-id build-config] :as proc-state}]
  (let [host ;; FIXME: get this from somewhere so it isn't hardcoded
        "localhost"

        port
        8200

        {:keys [reload-with-state node-eval id console-support before-load after-load]}
        build-config]

    (update proc-state :compiler-state
      (fn [compiler-state]
        (-> compiler-state
            (update :closure-defines merge
              {"shadow.devtools.browser.enabled"
               true

               "shadow.devtools.browser.build_id"
               (name id)

               "shadow.devtools.browser.proc_id"
               (str proc-id)

               "shadow.devtools.browser.url"
               (str "http://" host ":" port "/ws/devtools/" (str proc-id))

               "shadow.devtools.browser.before_load"
               (when before-load
                 (str (cljs-comp/munge before-load)))

               "shadow.devtools.browser.after_load"
               (when after-load
                 (str (cljs-comp/munge after-load)))

               "shadow.devtools.browser.node_eval"
               (boolean node-eval)

               "shadow.devtools.browser.reload_with_state"
               (boolean reload-with-state)
               })

            (update-in [:modules (:default-module compiler-state) :entries] prepend 'shadow.devtools.browser)
            (cond->
              console-support
              (update-in [:modules (:default-module compiler-state) :entries] prepend 'shadow.devtools.console))
            )))))

(defn build-msg
  [build-state e]
  (let [output (get-in build-state [:channels :output])]

    (>!! output {:type :build-message
                 :msg e})
    (prn [:build-msg e])
    build-state))

(defn build-failure
  [build-state e]
  (let [output (get-in build-state [:channels :output])]

    (>!! output {:type :build-failure
                 :e e})
    (prn [:build-failure e])
    build-state))

(defn build-configure
  "configure the build according to build-mode & build-config in state"
  [{:keys [mode build-config] :as build-state}]
  (try
    (let [output
          (get-in build-state [:channels :output])

          compiler-state
          (-> (comp/init mode build-config {:logger (util/async-logger output)})
              (cond->
                (= mode :dev)
                (repl/prepare)))]

      (-> build-state
          (assoc :compiler-state compiler-state)
          (inject-devtools)))

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

        ;; FIXME: send warnings here instead of log-like messages
        (>!! output {:type :build-success})

        (assoc build-state :compiler-state compiler-state))
      (catch Exception e
        (build-failure build-state e)))))

(defmulti do-proc-control (fn [state msg] (:type msg)))

(defmethod do-proc-control :eval-start
  [state {:keys [id eval-out client-out]}]
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

(defmethod do-proc-control :configure
  [state {:keys [mode config] :as msg}]
  (-> state
      (assoc :build-config config :autobuild false :mode mode)))

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
        (do (>!! reply-to value)
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
              (repl/process-input compiler-state code)
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


