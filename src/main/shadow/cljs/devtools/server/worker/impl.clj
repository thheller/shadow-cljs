(ns shadow.cljs.devtools.server.worker.impl
  (:refer-clojure :exclude (compile))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.core.async :as async :refer (go >! <! >!! <!! alt!)]
            [clojure.tools.logging :as log]
            [cljs.compiler :as cljs-comp]
            [cljs.analyzer :as cljs-ana]
            [shadow.build.api :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.util :refer (reduce->)]
            [shadow.build :as build]
            [shadow.build.api :as build-api]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.build.warnings :as warnings]
            [shadow.debug :as dbg]
            [shadow.build.data :as data]))

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
                (select-keys src [:resource-id
                                  :type
                                  :resource-name
                                  :output-name
                                  :from-jar
                                  :ns
                                  :provides]))))
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
  (>!!output worker-state
    {:type :build-failure
     :build-config build-config
     :report
     (binding [warnings/*color* false]
       (errors/error-format e))
     :e e
     }))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [build-config proc-id http executor npm babel classpath cache-root] :as worker-state}]

  (>!!output worker-state {:type :build-configure
                           :build-config build-config})

  (try
    ;; FIXME: allow the target-fn read-only access to worker-state? not just worker-info?
    ;; it may want to put things on the websocket?
    (let [worker-info
          {:proc-id proc-id
           :addr (:addr http)
           :host (:host http)
           :port (:port http)
           :ssl (:ssl http)}

          build-state
          (-> (util/new-build build-config :dev {})
              (build-api/with-logger (util/async-logger (-> worker-state :channels :output)))
              (assoc
                :worker-info worker-info
                :mode :dev
                ::compile-attempt 0)
              (build/configure :dev build-config))]

      ;; FIXME: should maybe cleanup old :build-state if there is one (re-configure)
      (assoc worker-state :build-state build-state))
    (catch Exception e
      (-> worker-state
          (dissoc :build-state) ;; just in case there is an old one
          (build-failure e)))))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

(defn extract-reload-info
  "for every cljs build source collect all defs that are marked with special metadata"
  [{::build/keys [config] :keys [compiler-env build-sources] :as build-state}]
  (let [{:keys [after-load after-load-async before-load before-load-async] :as devtools-config}
        (get-in build-state [::build/config :devtools])

        add-entry
        (fn [entries fn-sym & extra-attrs]
          (conj entries
            (-> {:fn-sym fn-sym
                 :fn-str (cljs-comp/munge (str fn-sym))}
                (cond->
                  (seq extra-attrs)
                  (merge (apply array-map extra-attrs))))))]

    (-> {:never-load #{}
         :after-load []
         :before-load []}
        (reduce->
          (fn [info {:keys [name meta defs] :as ns-info}]
            (-> info
                (cond->
                  (or (:dev/never-load meta)
                      (:dev/once meta) ;; can't decide on which meta to use
                      (:figwheel-noload meta))
                  (update :never-load conj name))

                (reduce-kv->
                  (fn [info def-sym {:keys [name meta] :as def-info}]
                    (cond-> info
                      (or (:dev/before-load meta)
                          (= name before-load))
                      (update :before-load add-entry name)

                      (or (:dev/before-load-async meta)
                          (= name before-load-async))
                      (update :before-load add-entry name :async true)

                      (or (:dev/after-load meta)
                          (= name after-load))
                      (update :after-load add-entry name)

                      (or (:dev/after-load-async meta)
                          (= name after-load-async))
                      (update :after-load add-entry name :async true)
                      ))
                  defs)))
          (->> build-sources
               (map #(data/get-source-by-id build-state %))
               (filter #(= :cljs (:type %)))
               ;; should filter more resources that don't contain hooks
               ;; processing might become slow when processing too many namespaces?
               ;; Bruce Hauman added ^:figwheel-hooks on the ns to filter
               ;; but I think that puts unnecessary burden on the user to "tag" twice
               ;; should this ever become a bottleneck we can optimize this by only checking
               ;; for hooks once after compile and cache it
               ;; FIXME: maybe add cache
               (remove (fn [{:keys [resource-name] :as rc}]
                         (or (str/starts-with? resource-name "cljs/")
                             (str/starts-with? resource-name "clojure/"))))
               (map :ns)
               (map #(get-in compiler-env [::cljs-ana/namespaces %]))))
        )))

(defn build-compile
  [{:keys [build-state] :as worker-state}]
  ;; this may be nil if configure failed, just silently do nothing for now
  (if (nil? build-state)
    worker-state
    (try
      (>!!output worker-state {:type :build-start
                               :build-config (::build/config build-state)})

      (let [{:keys [build-sources build-macros] :as build-state}
            (-> build-state
                (build-api/reset-always-compile-namespaces)
                (build/compile)
                (build/flush)
                (update ::compile-attempt inc))]

        (>!!output worker-state
          {:type :build-complete
           :build-config (::build/config build-state)
           :info (::build/build-info build-state)
           :reload-info (extract-reload-info build-state)})

        (assoc worker-state
          :build-state build-state
          :last-build-provides (-> build-state :sym->id keys set)
          :last-build-sources build-sources
          :last-build-macros build-macros))
      (catch Exception e
        (build-failure worker-state e)))))

(defn process-repl-result
  [{:keys [pending-results] :as worker-state} {:keys [id] :as result}]

  ;; forward everything to out as well
  (>!!output worker-state {:type :repl/result :result result})

  (let [reply-to
        (get pending-results id)]

    (log/debug ::repl-result id (nil? reply-to) (pr-str result))

    (if (nil? reply-to)
      worker-state

      (do (>!! reply-to result)
          (update worker-state :pending-results dissoc id))
      )))

(defmulti do-proc-control
  (fn [worker-state {:keys [type] :as msg}]
    type))

(defmethod do-proc-control :sync!
  [worker-state {:keys [chan] :as msg}]
  (async/close! chan)
  worker-state)

(defmethod do-proc-control :runtime-connect
  [{:keys [build-state] :as worker-state} {:keys [runtime-id runtime-out]}]
  (log/debug ::runtime-connect runtime-id)
  (>!! runtime-out {:type :repl/init
                    :repl-state
                    (-> (:repl-state build-state)
                        (update :repl-sources repl-sources-as-client-resources build-state))})

  (>!!output worker-state {:type :repl/runtime-connect :runtime-id runtime-id})
  (update worker-state :runtimes assoc runtime-id
    {:runtime-id runtime-id
     :runtime-out runtime-out
     :init-sent true}))

(defmethod do-proc-control :runtime-disconnect
  [worker-state {:keys [runtime-id]}]
  (log/debug ::runtime-disconnect runtime-id)
  (>!!output worker-state {:type :repl/runtime-disconnect :runtime-id runtime-id})
  (update worker-state :runtimes dissoc runtime-id))

(defmethod do-proc-control :client-start
  [worker-state {:keys [id in]}]
  (log/debug ::client-start id)
  (>!!output worker-state {:type :repl/client-start :id id})
  (update worker-state :repl-clients assoc id {:id id :in in}))

(defmethod do-proc-control :client-stop
  [worker-state {:keys [id]}]
  (log/debug ::client-stop id)
  (>!!output worker-state {:type :repl/client-stop :id id})
  (update worker-state :repl-clients dissoc id))

;; messages received from the runtime
(defmethod do-proc-control :runtime-msg
  [worker-state {:keys [msg runtime-id runtime-out] :as envelope}]
  (log/debug ::runtime-msg runtime-id (:type msg))

  (case (:type msg)
    (:repl/result
      :repl/invoke-error
      :repl/init-complete
      :repl/set-ns-complete
      :repl/require-complete)
    (process-repl-result worker-state msg)

    :repl/out
    (>!!output worker-state {:type :repl/out :text (:text msg)})

    :repl/err
    (>!!output worker-state {:type :repl/err :text (:text msg)})

    :ping
    (do (>!! runtime-out {:type :pong :v (:v msg)})
        worker-state)

    ;; unknown message
    (do (log/warn "runtime sent unknown msg" runtime-id msg)
        worker-state)))

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
  [{:keys [build-state] :as worker-state} {:keys [reply-to] :as msg}]
  (let [result
        (-> worker-state
            (cond->
              (not build-state)
              (build-configure))
            (build-compile))]

    (when reply-to
      (>!! reply-to :done))

    result
    ))

(defmethod do-proc-control :stop-autobuild
  [worker-state msg]
  (assoc worker-state :autobuild false))

(defmethod do-proc-control :load-file
  [{:keys [build-state runtimes] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [runtime-count (count runtimes)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (> runtime-count 1)
      (do (>!! result-chan {:type :repl/too-many-runtimes :count runtime-count})
          worker-state)

      (zero? runtime-count)
      (do (>!! result-chan {:type :repl/no-runtime-connected})
          worker-state)

      :else
      (try
        (let [{:keys [runtime-out] :as runtime}
              (first (vals runtimes))

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (repl/repl-load-file* build-state msg)

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              last-idx
              (-> (get-in build-state [:repl-state :repl-actions])
                  (count)
                  (dec))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            (>!!output worker-state {:type :repl/action
                                     :action action})
            (>!! runtime-out (transform-repl-action build-state action)))

          (-> worker-state
              (assoc :build-state build-state)
              (update :pending-results assoc last-idx result-chan)))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defmethod do-proc-control :repl-compile
  [{:keys [build-state] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (try
    (let [start-idx
          (count (get-in build-state [:repl-state :repl-actions]))

          {:keys [repl-state] :as build-state}
          (if (string? input)
            (repl/process-input build-state input)
            (repl/process-read-result build-state input))

          new-actions
          (subvec (:repl-actions repl-state) start-idx)]

      (>!! result-chan {:type :repl/actions
                        :actions new-actions})

      (assoc worker-state :build-state build-state))

    (catch Exception e
      (>!! result-chan {:type :repl/error :e e})
      worker-state)))

(defmethod do-proc-control :repl-eval
  [{:keys [build-state runtimes] :as worker-state}
   {:keys [result-chan input] :as msg}]
  (let [runtime-count (count runtimes)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (> runtime-count 1)
      (do (>!! result-chan {:type :repl/too-many-runtimes :count runtime-count})
          worker-state)

      (zero? runtime-count)
      (do (>!! result-chan {:type :repl/no-runtime-connected})
          worker-state)

      :else
      (try
        (let [{:keys [runtime-out] :as runtime}
              (first (vals runtimes))

              start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (if (string? input)
                (repl/process-input build-state input)
                (repl/process-read-result build-state input))

              new-actions
              (subvec (:repl-actions repl-state) start-idx)

              last-idx
              (-> (get-in build-state [:repl-state :repl-actions])
                  (count)
                  (dec))]

          (doseq [[idx action] (map-indexed vector new-actions)
                  :let [idx (+ idx start-idx)
                        action (assoc action :id idx)]]
            (>!!output worker-state {:type :repl/action
                                     :action action})
            (>!! runtime-out (transform-repl-action build-state action)))

          (-> worker-state
              (assoc :build-state build-state)
              ;; FIXME: now dropping intermediate results since the REPL only expects one result
              (update :pending-results assoc last-idx result-chan)))

        (catch Exception e
          (let [msg (repl-error e)]
            (>!! result-chan msg)
            (>!!output worker-state msg))
          worker-state)))))

(defn do-macro-update
  [{:keys [build-state last-build-macros autobuild] :as worker-state} {:keys [macro-namespaces] :as msg}]

  (cond
    (not build-state)
    worker-state

    ;; the updated macro may not be used by this build
    ;; so we can skip the rebuild
    (and (seq last-build-macros) (some last-build-macros macro-namespaces))
    (-> worker-state
        (update :build-state build-api/reset-resources-using-macros macro-namespaces)
        (cond->
          autobuild
          (build-compile)))

    :do-nothing
    (do (log/debug "build not affected by macros" macro-namespaces (get-in build-state [:build-config :build-id]))
        worker-state)))

(defn do-resource-update
  [{:keys [autobuild last-build-provides build-state] :as worker-state}
   {:keys [namespaces added] :as msg}]

  ;; configuration errors mean to build state, no updates affect this
  (if-not build-state
    worker-state

    (let [namespaces-used-by-build
          (->> namespaces
               (filter #(contains? last-build-provides %))
               (into #{}))]

      (cond
        ;; always recompile if the first compile attempt failed
        ;; since we don't know which files it wanted to use
        ;; after that only recompile when files in use by the build changed
        ;; or when new files were added and the build is greedy
        ;; (eg. browser-test since it dynamically adds file to the build)
        (and (pos? (::compile-attempt build-state))
             (not (seq namespaces-used-by-build))
             (if-not (get-in build-state [:build-options :greedy])
               ;; build is not greedy, not interested in new files
               true
               ;; build is greedy, must recompile if new files were added
               (not (seq added))))
        worker-state

        :else
        (let [build-state (build-api/reset-namespaces build-state namespaces-used-by-build)]

          (-> worker-state
              (assoc :build-state build-state)
              (cond->
                autobuild
                (build-compile))))
        ))))

(defn do-asset-update
  [{:keys [runtimes] :as worker-state} updates]
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
      (doseq [{:keys [runtime-out]} (vals runtimes)]
        (>!! runtime-out {:type :asset-watch
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

(defn repl-runtime-connect
  [{:keys [proc-stop proc-control] :as proc} runtime-id runtime-out]
  {:pre [(proc? proc)]}

  ;; runtime-in - messages received from the runtime are put into runtime-in
  ;; runtime-out - direct line to runtime
  ;; runtime-out-bridge - forwards to runtime-out. bridged so this loop can shutdown properly when the worker closes runtime-out
  (let [runtime-in
        (async/chan)

        runtime-out-bridge
        (async/chan)]

    ;; each ws gets it own connection loop which just forwards to the main worker
    ;; to ensure everything is executed in the worker thread.
    (go (>! proc-control {:type :runtime-connect
                          :runtime-id runtime-id
                          :runtime-out runtime-out-bridge})

        (loop []
          (alt!
            proc-stop
            ([_] nil)

            runtime-in
            ([msg]
              (when-not (nil? msg)
                (>! proc-control {:type :runtime-msg
                                  :runtime-id runtime-id
                                  :runtime-out runtime-out-bridge
                                  :msg msg})
                (recur)))

            runtime-out-bridge
            ([msg]
              (when-not (nil? msg)
                (>! runtime-out msg)
                (recur)))
            ))

        (>! proc-control {:type :runtime-disconnect
                          :runtime-id runtime-id})

        (async/close! runtime-out-bridge)
        (async/close! runtime-in))

    runtime-in))

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


