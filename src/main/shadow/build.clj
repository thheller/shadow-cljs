(ns shadow.build
  (:refer-clojure :exclude (resolve compile flush))
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [shadow.jvm-log :as log]
    [clojure.set :as set]
    [shadow.cljs.util :as util]
    [shadow.build.api :as build-api]
    [shadow.build.config :as config]
    [shadow.build.closure :as closure]
    [shadow.build.warnings :as warnings]
    [shadow.build.modules :as modules]
    [shadow.build.data :as data]
    [shadow.build.log :as build-log]
    [shadow.build.async :as async]
    [shadow.cljs.devtools.cljs-specs] ;; FIXME: move these
    ))

(defn enhance-warnings
  "adds source excerpts to warnings if line information is available"
  [state {:keys [resource-id file resource-name warnings] :as rc}]
  (let [{:keys [warnings source] :as output}
        (data/get-output! state rc)]

    (if (or (not (seq warnings))
            (not (string? source)))
      []
      (let [warnings
            (into [] (distinct warnings))

            source-excerpts
            (try
              (warnings/get-source-excerpts-for-rc state rc warnings)
              (catch Exception e
                (log/debug-ex e ::get-source-excerpts {:warnings warnings :resource-id resource-id})
                nil))]

        (->> (map (fn [warning source-excerpt]
                    (-> warning
                        (assoc
                          :resource-name resource-name
                          :resource-id resource-id)
                        (cond->
                          file
                          (assoc :file (.getAbsolutePath file))
                          source-excerpt
                          (assoc :source-excerpt source-excerpt))))
               warnings source-excerpts)
             (into [])
             )))))

;; FIXME: this currently only returns recently compiled CLJS sources
(defn resources-compiled-recently
  "returns a vector of resource-ids that where compiled in the last compile call"
  [{:keys [build-sources compile-start] :as state}]
  (->> build-sources
       (filter
         (fn [src-id]
           (let [{:keys [cached compiled-at] :as rc}
                 (get-in state [:output src-id])]

             (and (not cached) (number? compiled-at) (> compiled-at compile-start))
             )))
       (into [])))

(defn extract-build-info [state]
  (let [source->module
        (reduce
          (fn [index {:keys [sources module-id]}]
            (reduce
              (fn [index source]
                (assoc index source module-id))
              index
              sources))
          {}
          (:build-modules state))

        compiled-sources
        (into #{} (resources-compiled-recently state))

        build-sources
        (->> (:build-sources state)
             (map (fn [src-id]
                    (let [{:keys [resource-name type output-name from-jar ns provides deps] :as rc}
                          (data/get-source-by-id state src-id)]
                      {:resource-id src-id
                       :resource-name resource-name
                       :output-name output-name
                       :type type
                       :ns ns
                       :provides provides
                       :deps (data/deps->syms state rc)
                       :module (get source->module src-id)
                       :warnings (enhance-warnings state rc)
                       :from-jar from-jar})))
             (into []))]

    {:sources build-sources
     :modules (:build-modules state)
     :compiled compiled-sources}))

(defn- update-build-info-after-compile
  [state]
  (update state ::build-info merge (extract-build-info state)))

(defmethod build-log/event->str ::process-stage
  [{:keys [target stage]}]
  (format "build target: %s stage: %s" target stage))

(defmethod build-log/event->str ::process-hook
  [{:keys [hook-id stage]}]
  (format "build hook: %s stage: %s" hook-id stage))

(defn execute-hooks [{::keys [build-id] :as state} stage]
  (reduce-kv
    (fn [state hook-id hook-fn]
      (try
        (util/with-logged-time [state {:type ::process-hook
                                       :stage stage
                                       :build-id build-id
                                       :hook-id hook-id}]
          (hook-fn state))
        (catch Exception e
          (log/warn-ex e ::hook-execute-ex {:hook-id hook-id
                                            :build-id build-id})
          state)))
    state
    (get-in state [:build-hooks stage])))

(defn process-stage
  [{::keys [config mode target-fn] :as state} stage optional?]
  (let [before
        (-> state
            (assoc ::stage stage)
            (assoc-in [::build-info :timings stage :enter] (System/currentTimeMillis)))

        after
        (util/with-logged-time [before {:type ::process-stage
                                        :target (:target config)
                                        :stage stage}]
          (target-fn before))]
    (if (and (not optional?) (identical? before after))
      (throw (ex-info "process didn't do anything on non-optional stage"
               {:stage stage :mode mode :config config}))
      (-> after
          (execute-hooks stage)
          (assoc-in [::build-info :timings stage :exit] (System/currentTimeMillis))))))

(defn config-merge [config mode]
  (let [mode-opts (get config mode)]
    (-> config
        (cond->
          mode-opts
          (build-api/deep-merge mode-opts))
        (dissoc :release :dev))))

(defonce target-require-lock (Object.))

(defn get-target-fn [target build-id]

  (let [target-fn-sym
        (cond
          (qualified-symbol? target)
          target

          (simple-symbol? target)
          (symbol (str target) "process")

          (keyword? target)
          (symbol (str "shadow.build.targets." (name target)) "process")

          :else
          (throw (ex-info (format "invalid target: %s" (pr-str target)) {:target target})))

        target-ns (-> target-fn-sym namespace symbol)]

    (locking target-require-lock
      (when-not (find-ns target-ns)
        (try
          (require target-ns)
          (catch Exception e
            (throw (ex-info
                     (format "failed to require :target %s for build %s" target build-id)
                     {:tag ::get-target-fn
                      :build-id build-id
                      :target target
                      :target-ns target-ns
                      :target-fn-sym target-fn-sym}
                     e)))))

      (let [fn (ns-resolve target-ns target-fn-sym)]
        (when-not fn
          (throw (ex-info (str "target-fn " target-fn-sym " not found") {:target target})))

        fn
        ))))

(defn configure-hooks-from-config [{::keys [build-id] :as state} build-hooks]
  (reduce-kv
    (fn [state hook-idx [hook-sym & args]]
      (try
        ;; require the ns
        (locking target-require-lock
          (-> hook-sym namespace symbol require))

        (let [hook-var (find-var hook-sym)
              {::keys [stages stage]} (meta hook-var)]
          (reduce
            (fn [state stage]
              (assoc-in state [:build-hooks stage [hook-idx hook-sym]]
                (fn [build-state]
                  (let [result (apply hook-var build-state args)]
                    (when-not (build-api/build-state? result)
                      (throw (ex-info "hook returned invalid result" {:type ::invalid-hook-result
                                                                      :hook-sym hook-sym
                                                                      :build-id build-id
                                                                      :stage stage})))
                    result))))
            state
            (or stages [stage])))
        (catch Exception e
          (log/warn-ex e ::hook-config-ex {:hook-idx hook-idx
                                           :hook-sym hook-sym
                                           :build-id build-id})
          state)))
    state
    build-hooks))

;; FIXME: this is kinda dirty but saves passing around a secondary js-options along npm
(defn copy-js-options-to-npm [{:keys [mode js-options] :as state}]
  (update-in state [:npm :js-options] merge js-options {:mode mode}))

(defn configure
  ([build-state mode config]
    (configure build-state mode config {}))
  ([build-state mode {:keys [build-id target] :as config} cli-opts]
    {:pre [(build-api/build-state? build-state)
           (map? config)
           (keyword? mode)
           (keyword? build-id)
           (some? target)]
     :post [(build-api/build-state? %)]}

    (let [{:keys [build-options closure-defines compiler-options js-options build-hooks] :as config}
          (-> config
              (config-merge mode)
              (util/reduce-> build-api/deep-merge (:config-merge cli-opts)))

          target-fn
          (get-target-fn target build-id)]

      ;; must do this after calling get-target-fn
      ;; the namespace that it is in may have added to the multi-spec
      (when-not (s/valid? ::config/build+target config)
        (throw (ex-info "invalid build config" (assoc (s/explain-data ::config/build+target config)
                                                 :tag ::config
                                                 :config config))))

      (when (contains? config :source-paths)
        (throw (ex-info
                 ":source-paths only work at the top level and not per build."
                 {:tag ::source-paths :config config})))

      (let [externs-file (io/file "externs" (str (name build-id) ".txt"))
            {:keys [devtools]} config]

        (-> build-state
            (assoc
              :build-id build-id
              ::build-id build-id
              ::stage :init
              ::config config
              ::target-fn target-fn
              :mode mode
              ::mode mode)
            ;; FIXME: not setting this for :release builds may cause errors
            ;; http://dev.clojure.org/jira/browse/CLJS-2002
            (update :runtime assoc :print-fn :console)

            (cond->
              (seq build-hooks)
              (configure-hooks-from-config build-hooks)

              ;; generic dev mode, each target can overwrite in :init stage
              (= :dev mode)
              (-> (build-api/enable-source-maps)
                  (build-api/with-build-options
                    {:use-file-min false})
                  (build-api/with-compiler-options
                    {:optimizations :none})
                  (update-in [:compiler-options :closure-defines] merge {'goog.DEBUG true})
                  (assoc :devtools devtools)
                  (build-api/with-js-options
                    {:variable-renaming :off}))

              ;; generic release mode
              (= :release mode)
              (-> (build-api/with-compiler-options
                    {:optimizations :advanced
                     :elide-asserts true
                     :load-tests false
                     :pretty-print false})
                  (build-api/with-js-options {:minimize-require true})
                  (update-in [:compiler-options :closure-defines] merge {'goog.DEBUG false}))

              closure-defines
              (update-in [:compiler-options :closure-defines] merge closure-defines)

              compiler-options
              (build-api/with-compiler-options compiler-options)

              build-options
              (build-api/with-build-options build-options)

              (.exists externs-file)
              (assoc :externs-file externs-file)

              js-options
              (build-api/with-js-options js-options)

              (and (= :dev mode)
                   (:keep-native-requires js-options))
              (update-in [:js-options :keep-as-require] util/set-conj "ws"))

            ;; should do all configuration necessary
            (process-stage :configure true)

            ;; :optimizations is ignored in dev mode
            ;; but cljs-devtools still reads it from the options and complains
            ;; when it is not equal to :none
            ;; so we overwrite whatever configure did since dev/release configs are shared
            (cond->
              (= :dev mode)
              (assoc-in [:compiler-options :optimizations] :none))

            (copy-js-options-to-npm)
            )))))

(defn- extract-build-macros [{:keys [build-sources] :as state}]
  (let [build-macros
        (->> build-sources
             (map #(data/get-source-by-id state %))
             (filter #(= :cljs (:type %)))
             (map :macro-requires)
             (reduce set/union #{}))]
    (assoc state :build-macros build-macros)
    ))

(defn compile-start [state]
  (assoc state ::build-info {:compile-cycle (::build-api/compile-cycle state)
                             :compile-start (System/currentTimeMillis)}))

(defn compile-complete [state]
  (-> state
      (update ::build-api/compile-cycle inc)
      (assoc-in [::build-info :compile-complete] (System/currentTimeMillis))))

(defn resolve [state]
  (if (or (not (modules/configured? state))
          (get-in state [:build-options :dynamic-resolve]))
    ;; flat build, no modules
    (process-stage state :resolve false)
    ;; :modules based build
    (modules/analyze state)))

(defn compile
  [{::keys [mode] :as state}]
  {:pre [(build-api/build-state? state)]
   :post [(build-api/build-state? %)]}
  (-> state
      (compile-start)
      (resolve)
      (extract-build-macros)
      (process-stage :compile-prepare true)
      (build-api/compile-sources)
      (update-build-info-after-compile)
      (process-stage :compile-finish true)
      (compile-complete)))

(defn optimize
  [{::keys [mode skip-optimize] :as state}]
  {:pre [(build-api/build-state? state)]
   :post [(build-api/build-state? %)]}
  (-> state
      (cond->
        (and (= :release mode) (not skip-optimize))
        (-> (process-stage :optimize-prepare true)
            (build-api/optimize)
            (process-stage :optimize-finish true)))))

(defn check
  [state]
  {:pre [(build-api/build-state? state)]
   :post [(build-api/build-state? %)]}
  (-> state
      (process-stage :check-prepare true)
      (closure/check)
      (process-stage :check-finish true)))

(defn flush
  [state]
  {:pre [(build-api/build-state? state)]
   :post [(build-api/build-state? %)]}
  (-> state
      (process-stage :flush true)
      ;; FIXME: technically don't need to wait for this to complete
      ;; but ensures that the build is actually complete with no pending tasks
      (async/wait-for-pending-tasks!)
      (assoc-in [::build-info :flush-complete] (System/currentTimeMillis))))


(defn log [state log-event]
  {:pre [(build-api/build-state? state)]}
  (build-log/log* (:logger state) state log-event)
  state)

