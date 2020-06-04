(ns shadow.cljs.devtools.server.worker.impl
  (:refer-clojure :exclude (compile))
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.core.async :as async :refer (go >! <! >!! <!! alt!)]
    [clojure.java.io :as io]
    [cljs.compiler :as cljs-comp]
    [cljs.analyzer :as cljs-ana]
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.jvm-log :as log]
    [shadow.cljs.repl :as repl]
    [shadow.cljs.model :as m]
    [shadow.cljs.util :as util :refer (set-conj reduce->)]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.build.api :as cljs]
    [shadow.build.async :as basync]
    [shadow.build.compiler :as build-comp]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.build.warnings :as warnings]
    [shadow.build.data :as data]
    [shadow.build.resource :as rc]
    [shadow.build.log :as build-log]
    [shadow.cljs.devtools.server.reload-npm :as reload-npm]
    [shadow.build.output :as output]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.shared :as shared])
  (:import [java.util UUID]
           [java.io File]))

(defn proc? [x]
  (and (map? x) (::proc x)))

(defn worker-state? [x]
  (and (map? x) (::worker-state x)))

(defn gen-msg-id []
  (str (UUID/randomUUID)))

(defn relay-msg
  ([worker-state res]
   (when-not (async/offer! (get-in worker-state [:channels :to-relay]) res)
     (log/warn ::worker-relay-overload res))
   worker-state)
  ([worker-state {:keys [from call-id] :as req} res]
   (relay-msg worker-state (cond-> res
                             from
                             (assoc :to from)
                             call-id
                             (assoc :call-id call-id)))))

(defn send-to-runtimes [{:keys [runtimes] :as worker-state} msg]
  ;; (log/debug ::send-to-runtimes {:runtimes runtimes :msg (:op msg)})
  (when (seq runtimes)
    (relay-msg worker-state (assoc msg :to (-> runtimes keys set))))
  worker-state)

(defn repl-sources-as-client-resources
  "transforms a seq of resource-ids to return more info about the resource
   a REPL client needs to know more since resource-ids are not available at runtime"
  [source-ids build-state]
  (->> source-ids
       (map (fn [src-id]
              (let [src (get-in build-state [:sources src-id])]
                (select-keys src [:resource-id
                                  :type
                                  :resource-name
                                  :output-name
                                  :from-jar
                                  :ns
                                  :provides]))))
       (into [])))

(defmulti transform-repl-action
  (fn [build-state action]
    (:type action))
  :default ::default)

(defmethod transform-repl-action ::default [build-state action]
  action)

(defmethod transform-repl-action :repl/require [build-state action]
  (update action :sources repl-sources-as-client-resources build-state))

(defn >!!output [{:keys [system-bus build-id] :as worker-state} msg]
  {:pre [(map? msg)
         (:type msg)]}

  (let [msg (assoc msg :build-id build-id)
        output (get-in worker-state [:channels :output])]

    (sys-bus/publish system-bus ::m/worker-broadcast msg)
    (sys-bus/publish system-bus [::m/worker-output build-id] msg)

    (>!! output msg)
    worker-state))

(defn build-msg
  [worker-state msg]
  (>!!output worker-state
    {:type :build-message
     :msg msg}))

(defn repl-error [e data]
  (log/warn-ex e ::repl-error data)
  {:type :repl/error
   :ex e})

;; if the build fails due to a missing js dependency
;; start watching package.json and attempt recompile on changes
(defn init-package-json-watch [worker-state {:keys [js-package-dirs] :as data}]
  (assoc worker-state
    :package-json-files
    (->> js-package-dirs
         (map (fn [modules-dir]
                (-> modules-dir
                    (.getParentFile)
                    (io/file "package.json"))))
         (filter #(.exists ^File %))
         (reduce
           (fn [m package-json-file]
             (assoc m package-json-file (.lastModified package-json-file)))
           {}))))

(defn build-failure
  [worker-state e]
  (let [{:keys [resource-id resource-ids tag] :as data} (ex-data e)]
    (let [error-report
          (binding [warnings/*color* false]
            (errors/error-format e))]
      (-> worker-state
          ;; if any resource was responsible for the build failing we remove it completely
          ;; to ensure that all state is in proper order in the next compile and does not
          ;; contain remnants of the failed compile
          ;; FIXME: should probably check ex-data :tag
          (assoc :failure-data data)
          (cond->
            (= tag :shadow.build.resolve/missing-js)
            (init-package-json-watch data)

            resource-id
            (update :build-state data/remove-source-by-id resource-id)
            resource-ids
            (update :build-state build-api/reset-resources resource-ids))

          (>!!output
            {:type :build-failure
             :report error-report})

          (send-to-runtimes {:op :cljs-build-failure
                             :report error-report})))))

(defn build-configure
  "configure the build according to build-config in state"
  [{:keys [system-bus build-id build-config proc-id http] :as worker-state}]

  (>!!output worker-state {:type :build-configure
                           :build-config build-config})

  (send-to-runtimes worker-state
    {:op :cljs-build-configure
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

          log-chan
          (-> worker-state :channels :output)

          async-logger
          (reify
            build-log/BuildLog
            (log*
              [_ state event]
              (sys-bus/publish system-bus ::m/build-log
                {:type :build-log
                 :build-id build-id
                 :event event})
              (async/offer! log-chan {:type :build-log :event event})))

          repl-init-ns
          (get-in build-config [:devtools :repl-init-ns] 'cljs.user)

          {:keys [npm extra-config-files] :as build-state}
          (-> (server-util/new-build build-config :dev (:cli-opts worker-state {}))
              (build-api/with-logger async-logger)
              (merge {:worker-info worker-info
                      :mode :dev
                      ::compile-attempt 0
                      ;; temp hack for repl/setup since it needs access to repl-init-ns but configure wasn't called yet
                      :shadow.build/config build-config
                      })
              ;; FIXME: work around issues where this is used by clients before a runtime connects
              (assoc :repl-state {:current-ns repl-init-ns})
              (build/configure :dev build-config (:cli-opts worker-state {}))
              (assoc-in [:compiler-options :closure-defines 'shadow.cljs.devtools.client.env/worker-client-id] (:relay-client-id worker-state))
              (assoc-in [:compiler-options :closure-defines 'shadow.cljs.devtools.client.env/server-token] (get-in worker-state [:http :server-token])))

          extra-config-files
          (reduce
            (fn [m file]
              (assoc m file (.lastModified file)))
            {}
            extra-config-files)]

      ;; FIXME: should maybe cleanup old :build-state if there is one (re-configure)
      (-> worker-state
          (cond->
            ;; stop old running npm watch in case of reconfigure
            (:reload-npm worker-state)
            (update :reload-npm reload-npm/stop))
          (assoc :extra-config-files extra-config-files
                 :reload-npm (reload-npm/start npm #(>!! (:resource-update-chan worker-state) %))
                 :build-state build-state)))
    (catch Exception e
      (-> worker-state
          (dissoc :build-state) ;; just in case there is an old one
          (build-failure e)))))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

(defn extract-reload-info
  "for every cljs build source collect all defs that are marked with special metadata"
  [{:keys [build-sources] :as build-state}]
  (-> {:never-load #{}
       :always-load #{}
       :after-load []
       :before-load []}
      (reduce->
        (fn [info resource-id]
          (let [hooks (get-in build-state [:output resource-id ::hooks])]
            (if-not hooks
              info
              (merge-with into info hooks)
              )))
        build-sources
        )))

(defn add-entry [entries fn-sym & extra-attrs]
  (conj entries
    (-> {:fn-sym fn-sym
         :fn-str (cljs-comp/munge (str fn-sym))}
        (cond->
          (seq extra-attrs)
          (merge (apply array-map extra-attrs))))))

(defn build-find-resource-hooks [{:keys [compiler-env] :as build-state} {:keys [resource-id ns] :as src}]
  (let [{:keys [name meta defs] :as ns-info}
        (get-in compiler-env [::cljs-ana/namespaces ns])

        {:keys [after-load after-load-async before-load before-load-async] :as devtools-config}
        (get-in build-state [::build/config :devtools])

        hooks
        (-> {:never-load #{}
             :after-load []
             :before-load []}
            (cond->
              (or (:dev/never-load meta)
                  (:dev/once meta) ;; can't decide on which meta to use
                  (:figwheel-noload meta))
              (update :never-load conj name)

              (or (:dev/always-load meta)
                  (:dev/always meta)
                  (:figwheel-always meta))
              (update :always-load conj name))

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
                  (update :after-load add-entry name :async true)))
              defs))]

    (assoc-in build-state [:output resource-id ::hooks] hooks)))

(defn build-find-hooks [{:keys [build-sources] :as state}]
  (reduce
    (fn [state resource-id]
      (let [{:keys [type resource-name] :as src}
            (data/get-source-by-id state resource-id)]
        (if (or (not= :cljs type)
                (get-in state [:output resource-id ::hooks])
                (str/starts-with? resource-name "cljs/")
                (str/starts-with? resource-name "clojure/"))
          state
          (build-find-resource-hooks state src))))
    state
    build-sources))

(defn collect-resource-refs [{:keys [build-sources compiler-env] :as build-state}]
  (->> build-sources
       (map #(get-in build-state [:sources %]))
       (filter #(= :cljs (:type %)))
       (reduce
         (fn [m {:keys [ns] :as rc}]
           (let [refs (get-in compiler-env [::cljs-ana/namespaces ns :shadow.resource/resource-refs])]
             (reduce-kv
               (fn [m path last-mod]
                 (-> m
                     (update-in [:used-by path] set-conj ns)
                     (update :used-ts assoc path last-mod)))
               m
               refs)))
         {:used-by {}
          :used-ts {}})))

;; always initializing the REPL state since tools may want to look at the analyzer state
;; without actually evaling anything or a runtime being present, should at least
;; have compiled the init namespaces for the REPL
(defn ensure-repl-init [build-state]
  ;; FIXME: repl-state should be coupled to the client "session" not the runtime
  (if (seq (get-in build-state [:repl-state :repl-sources]))
    build-state
    ;; ensure that all REPL related things have been compiled
    ;; so the runtime can properly load them
    (repl/prepare build-state)))

(defn build-compile
  [{:keys [build-state macros-modified namespaces-modified] :as worker-state}]
  ;; this may be nil if configure failed, just silently do nothing for now
  (if (nil? build-state)
    worker-state
    (try
      (>!!output worker-state {:type :build-start})
      (send-to-runtimes worker-state {:op :cljs-build-start})

      (let [{:keys [build-sources build-macros] :as build-state}
            (-> build-state
                (cond->
                  (seq namespaces-modified)
                  (build-api/reset-namespaces namespaces-modified)

                  (seq macros-modified)
                  (build-api/reset-resources-using-macros macros-modified))

                (build-api/reset-always-compile-namespaces)
                (build/compile)
                (build/flush)
                (build-find-hooks)
                (ensure-repl-init)
                (update ::compile-attempt inc))]

        (let [info (::build/build-info build-state)
              reload-info (extract-reload-info build-state)
              msg {:type :build-complete
                   :info info
                   :reload-info reload-info}]
          (>!!output worker-state msg)
          (send-to-runtimes worker-state {:op :cljs-build-complete
                                          :info info
                                          :reload-info reload-info}))

        (let [none-code-resources (collect-resource-refs build-state)]

          (-> worker-state
              (dissoc :failure-data)
              (assoc :build-state build-state
                     ;; tracking added/modified namespaces since we finished compiling
                     :namespaces-added #{}
                     :namespaces-modified #{}
                     :macros-modified #{}
                     :last-build-resources none-code-resources
                     :last-build-provides (-> build-state :sym->id keys set)
                     :last-build-sources build-sources
                     :last-build-macros build-macros))))
      (catch Exception e
        (build-failure worker-state e)))))

(defmulti do-proc-control
  (fn [worker-state {:keys [type] :as msg}]
    type))

(defmethod do-proc-control :sync!
  [worker-state {:keys [chan] :as msg}]
  (async/close! chan)
  worker-state)

(defn maybe-pick-different-default-runtime [{:keys [runtimes default-runtime-id] :as worker-state} runtime-id]
  (cond
    (not= default-runtime-id runtime-id)
    worker-state

    (empty? runtimes)
    (dissoc worker-state :default-runtime-id)

    :else
    (let [new-default
          (->> (vals runtimes)
               (sort-by :connected-since)
               (first)
               :client-id)]

      (if-not new-default
        (dissoc worker-state :default-runtime-id)
        (assoc worker-state :default-runtime-id new-default)))))

(defn remove-runtime [worker-state runtime-id]
  (-> worker-state
      (update :runtimes dissoc runtime-id)
      (maybe-pick-different-default-runtime runtime-id)))

(defmethod do-proc-control :start-autobuild
  [{:keys [build-state autobuild] :as worker-state} msg]
  (if autobuild
    ;; do nothing if already in auto mode
    worker-state
    ;; compile immediately, autobuild is then checked later
    (-> worker-state
        (assoc :autobuild true)
        (cond->
          (not build-state)
          (build-configure))
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

(defn do-repl-rpc
  [{:keys [build-state runtimes default-runtime-id] :as worker-state}
   command
   {:keys [result-chan session-id runtime-id] :as msg}]
  (log/debug ::repl-rpc {:command command :session-id session-id :runtime-id runtime-id})
  (let [runtime-count (count runtimes)

        runtime-id
        (or runtime-id default-runtime-id)]

    (cond
      (nil? build-state)
      (do (>!! result-chan {:type :repl/illegal-state})
          worker-state)

      (and (not runtime-id) (> runtime-count 1))
      (do (>!! result-chan {:type :repl/too-many-runtimes :count runtime-count})
          worker-state)

      (and runtime-id (not (get runtimes runtime-id)))
      (do (>!! result-chan {:type :repl/runtime-not-found :runtime-id runtime-id})
          worker-state)

      (zero? runtime-count)
      (do (>!! result-chan {:type :repl/no-runtime-connected})
          worker-state)

      :else
      (try
        (let [start-idx
              (count (get-in build-state [:repl-state :repl-actions]))

              {:keys [repl-state] :as build-state}
              (case command
                :load-file
                (repl/repl-load-file* build-state msg)

                :repl-eval
                (let [{:keys [input]} msg]
                  (if (string? input)
                    (repl/process-input build-state input)
                    (repl/process-read-result build-state input)))

                (throw (ex-info "unknown repl rpc" {:msg msg :command command})))

              new-actions
              (->> (subvec (:repl-actions repl-state) start-idx)
                   (map #(transform-repl-action build-state %))
                   (vec))

              eval-id
              (str (UUID/randomUUID))]

          (relay-msg worker-state
            {:op :cljs-repl-actions
             :to runtime-id
             :call-id eval-id ;; fake call, just so runtime can use built-in reply
             :actions new-actions})

          (-> worker-state
              (assoc :build-state build-state)
              (assoc-in [:pending-results eval-id] msg)))

        (catch Exception e
          (let [msg (repl-error e {:when ::do-repl-rpc :command command :msg msg})]
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
    (and (seq last-build-macros))
    (-> worker-state
        (update :build-state build-api/reset-resources-using-macros macro-namespaces)
        (cond->
          autobuild
          (build-compile)))

    :do-nothing
    (do (log/debug ::not-affected {:build-id (get-in build-state [:build-config :build-id])
                                   :macro-namespaces macro-namespaces})
        worker-state)))

(defn do-resource-update
  [{:keys [autobuild last-build-macros last-build-provides build-state] :as worker-state}
   {:keys [namespaces added macros] :as msg}]

  (if-not build-state
    worker-state
    (let [namespaces-used-by-build
          (->> namespaces
               (filter #(contains? last-build-provides %))
               (into #{}))

          macros-used-by-build
          (when (seq last-build-macros)
            (->> macros
                 (filter last-build-macros)
                 (set)))]

      (cond
        ;; always recompile if the first compile attempt failed
        ;; since we don't know which files it wanted to use
        ;; after that only recompile when files in use by the build changed
        ;; or when new files were added and the build is greedy
        ;; (eg. browser-test since it dynamically adds file to the build)
        (and (pos? (::compile-attempt build-state))
             (not (seq namespaces-used-by-build))
             (not (seq macros-used-by-build))
             (if-not (get-in build-state [:build-options :greedy])
               ;; build is not greedy, not interested in new files
               true
               ;; build is greedy, must recompile if new files were added
               (not (seq added))))
        worker-state

        :else
        (-> worker-state
            ;; only record which namespaces where added/changed
            ;; delay doing actual work until next compilation
            ;; which may not happen immediately if autobuild is off
            ;; the REPL may still trigger compilations independently
            ;; which break if the state is already half cleaned
            (update :namespaces-added set/union added)
            (update :namespaces-modified set/union added namespaces)
            (update :macros-modified set/union macros-used-by-build)
            (cond->
              autobuild
              (build-compile)))))))

(defn do-asset-update
  [worker-state {:keys [updates] :as msg}]

  (if-not (seq updates)
    worker-state
    (let [to (->> (:runtimes worker-state)
                  (vals)
                  (filter :dom)
                  (map :client-id)
                  (into #{}))]

      (cond-> worker-state
        (seq to)
        (relay-msg
          {:op :cljs-asset-update
           :to to
           :updates updates}
          )))))

(defn do-config-watch
  [{:keys [autobuild] :as worker-state} {:keys [config] :as msg}]
  (-> worker-state
      (assoc :build-config config)
      (cond->
        autobuild
        (-> (build-configure)
            (build-compile)))))

(defn check-extra-files [files]
  (reduce-kv
    (fn [m file last-mod]
      (let [new-mod (.lastModified file)]
        (if (= new-mod last-mod)
          m
          (assoc m file new-mod))))
    files
    files))

(defn maybe-reload-config-files [{:keys [autobuild extra-config-files] :as worker-state}]
  (let [checked (check-extra-files extra-config-files)]
    (if (identical? extra-config-files checked)
      worker-state
      (do (log/debug ::extra-files-modified)
          (-> worker-state
              (assoc :addition-config-files checked)
              (cond->
                autobuild
                (-> (build-configure)
                    (build-compile)))
              )))))

(defn maybe-recover-from-failure
  [{:keys [package-json-files] :as worker-state}]
  (reduce-kv
    (fn [worker-state file last-mod]
      (let [newmod (.lastModified file)]
        (if-not (> newmod last-mod)
          worker-state
          (do (log/debug ::package-json-modified)
              (-> worker-state
                  (dissoc :package-json-files)
                  (cond->
                    (not (:build-state worker-state))
                    (build-configure))
                  (build-compile)
                  (reduced))))))
    worker-state
    package-json-files))

(defn check-none-code-resources [{:keys [last-build-resources] :as worker-state}]
  (let [{:keys [used-ts used-by]}
        last-build-resources

        modified-namespaces
        (reduce-kv
          (fn [namespaces path prev-mod]
            (let [rc (io/resource path)]
              (if (or (not rc)
                      (not= prev-mod (util/url-last-modified rc)))
                (let [used-by-namespaces (get used-by path)]
                  (log/debug ::resource-modified {:path path :used-by used-by-namespaces})
                  (into namespaces used-by-namespaces))
                namespaces
                )))
          #{}
          used-ts)]

    (-> worker-state
        (cond->
          (seq modified-namespaces)
          (-> (update :namespaces-modified into modified-namespaces)
              (build-compile))))))

(defn send-runtime-ping [worker-state runtime-id now]
  (relay-msg worker-state
    {:op :cljs-repl-ping
     :to runtime-id
     :time-server now})
  (assoc-in worker-state [:runtimes runtime-id :last-ping] now))

(defn maybe-send-runtime-pings [{:keys [runtimes] :as worker-state}]
  ;; time doesn't need to accurate, so use the same time for all pings
  (let [now (System/currentTimeMillis)]
    (reduce-kv
      (fn [worker-state runtime-id {:keys [last-ping]}]
        (let [diff (- now (or last-ping 0))]
          (if (< diff 30000)
            worker-state
            (send-runtime-ping worker-state runtime-id now)
            )))
      worker-state
      runtimes)))

(defn do-idle [{:keys [failure-data extra-config-files] :as worker-state}]
  (-> worker-state
      (maybe-send-runtime-pings)
      (cond->
        (seq extra-config-files)
        (maybe-reload-config-files)

        failure-data
        (maybe-recover-from-failure)

        (seq (get-in worker-state [:last-build-resources :used-ts]))
        (check-none-code-resources))))

(defmulti do-relay-msg (fn [worker-state msg] (:op msg)) :default ::default)

(defmethod do-relay-msg ::default [worker-state msg]
  (log/warn ::unhandled-op msg)
  worker-state)

(defmethod do-relay-msg :unknown-op [worker-state msg]
  (log/warn ::unknown-op msg)
  worker-state)

(defmethod do-relay-msg :unknown-relay-op [worker-state msg]
  (log/warn ::unknown-relay-op msg)
  worker-state)

(defmethod do-relay-msg :request-supported-ops [worker-state msg]
  (relay-msg worker-state msg
    {:op :supported-ops
     :ops (-> (->> (methods do-relay-msg)
                   (keys)
                   (set))
              (disj ::default
                :welcome
                :unknown-op
                :unknown-relay-op
                :tool-disconnect
                :request-supported-ops))}))

(defmethod do-relay-msg :tool-disconnect
  [worker-state msg]
  worker-state)

;; if relay doesn't know the runtime anymore
(defmethod do-relay-msg :client-not-found
  [worker-state {:keys [client-id]}]
  (log/debug ::client-not-found {:runtime-id client-id})
  (remove-runtime worker-state client-id))

(defmethod do-relay-msg :cljs-compile
  [{:keys [build-state] :as worker-state}
   {:keys [input include-init] :as msg}]
  (try
    (let [start-idx
          (count (get-in build-state [:repl-state :repl-actions]))

          {:keys [code]} input

          {:keys [repl-state] :as build-state}
          (repl/process-input build-state code input)

          new-actions
          (->> (subvec (:repl-actions repl-state) start-idx)
               (mapv #(transform-repl-action build-state %)))]

      (relay-msg worker-state msg
        {:op :cljs-compile-result
         :actions
         (if-not include-init
           new-actions

           (into
             [{:type :repl/init
               :repl-state (-> (:repl-state build-state)
                               (update :repl-sources repl-sources-as-client-resources build-state))}]
             new-actions))})

      (assoc worker-state :build-state build-state))

    (catch Exception e
      (log/warn-ex e ::cljs-compile-ex {:input input})

      (let [{:keys [clj-obj-support clj-runtime]} worker-state
            ex-oid (obj-support/register clj-obj-support e {:msg msg})
            ex-client-id (shared/get-client-id clj-runtime)]

        (relay-msg worker-state msg
          {:op :cljs-compile-error
           ;; just send oid reference, ui can request report
           ;; FIXME: not really, need somehow enable that via protocol impl?
           :ex-oid ex-oid
           :ex-client-id ex-client-id
           ;; just always include report for now
           :report (binding [warnings/*color* false]
                     (errors/error-format e))
           }))

      worker-state)))

(defmethod do-relay-msg :cljs-load-sources
  [{:keys [build-state] :as worker-state}
   {:keys [sources] :as msg}]

  (let [module-format
        (get-in build-state [:build-options :module-format])]

    (relay-msg worker-state msg
      {:op :cljs-sources
       :sources
       (->> sources
            (map (fn [src-id]
                   (assert (rc/valid-resource-id? src-id))
                   (let [{:keys [resource-name type output-name ns provides] :as src}
                         (data/get-source-by-id build-state src-id)

                         {:keys [js] :as output}
                         (data/get-output! build-state src)]

                     {:resource-name resource-name
                      :resource-id src-id
                      :output-name output-name
                      :type type
                      :ns ns
                      :provides provides

                      ;; FIXME: make this pretty ...
                      :js
                      (case module-format
                        :goog
                        (let [sm-text (output/generate-source-map-inline build-state src output "")]
                          (str js sm-text))
                        :js
                        (let [prepend
                              (output/js-module-src-prepend build-state src false)

                              append
                              "" #_(output/js-module-src-append build-state src)

                              sm-text
                              (output/generate-source-map-inline build-state src output prepend)]

                          (str prepend js append sm-text)))
                      })))
            (into []))})

    worker-state))

(defn add-runtime
  [worker-state {:keys [client-id client-info] :as msg}]

  (-> worker-state
      (cond->
        (or (zero? (count (:runtimes worker-state)))
            ;; android doesn't disconnect the old websocket for some reason
            ;; when reloading the app, so instead of sending to a dead runtime
            ;; we always pick the new one
            (:react-native client-info)
            ;; allow user to configure to auto switch to fresh connected runtimes
            ;; instead of staying with the first connected one
            (= :latest (get-in worker-state [:system-config :repl :runtime-select]))
            (= :latest (get-in worker-state [:system-config :user-config :repl :runtime-select])))
        (assoc :default-runtime-id client-id))
      (update :runtimes assoc client-id (assoc client-info :client-id client-id))))

(defmethod do-relay-msg ::cljs-runtime-notify
  [worker-state {:keys [event-op client-id] :as msg}]
  (log/debug ::notify msg)
  (case event-op
    :client-disconnect
    (remove-runtime worker-state client-id)
    :client-connect
    (add-runtime worker-state msg)))

(defmethod do-relay-msg :cljs-repl-pong
  [worker-state {:keys [from time-runtime]}]
  (update-in worker-state [:runtimes from] merge {:last-pong (System/currentTimeMillis)
                                                  :last-pong-runtime time-runtime}))
