(ns shadow.build
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [shadow.build.api :as build-api]
            [shadow.build.config :as config]
            [shadow.build.closure :as closure]
            [shadow.build.warnings :as warnings]
            [shadow.build.modules :as modules]

    ;; FIXME: move these
            [shadow.cljs.devtools.cljs-specs]
            [shadow.build.data :as data]
            [clojure.tools.logging :as log]))

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
            (warnings/get-source-excerpts state rc warnings)]

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

(defn extract-build-info [{:keys [dead-js-deps] :as state}]
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
                    (let [{:keys [resource-name type output-name from-jar ns] :as rc}
                          (data/get-source-by-id state src-id)]
                      {:resource-id src-id
                       :resource-name resource-name
                       :output-name output-name
                       :type type
                       :ns ns
                       :module (get source->module src-id)
                       :warnings (enhance-warnings state rc)
                       :from-jar from-jar})))
             (remove #(contains? dead-js-deps (:ns %)))
             (into []))]

    {:sources build-sources
     :compiled compiled-sources}))

(defn- update-build-info-from-modules
  [{:keys [build-modules] :as state}]
  (update state ::build-info merge {:modules build-modules}))

(defn- update-build-info-after-compile
  [state]
  (update state ::build-info merge (extract-build-info state)))

(defn process-stage
  [{::keys [config mode target-fn] :as state} stage optional?]
  (let [before
        (-> state
            (assoc ::stage stage)
            (assoc-in [::build-info :timings stage :enter] (System/currentTimeMillis)))

        after
        (target-fn before)]
    (if (and (not optional?) (identical? before after))
      (throw (ex-info "process didn't do anything on non-optional stage"
               {:stage stage :mode mode :config config}))
      (assoc-in after [::build-info :timings stage :exit] (System/currentTimeMillis)))))

(defn deep-merge [a b]
  (cond
    (nil? a)
    b

    (nil? b)
    a

    (and (map? a) (map? b))
    (merge-with deep-merge a b)

    (and (vector? a) (vector? b))
    (->> (concat a b)
         (distinct)
         (into []))

    (string? b)
    b

    (number? b)
    b

    (boolean? b)
    b

    :else
    (throw (ex-info "failed to merge config value" {:a a :b b}))
    ))

(defn config-merge [config mode]
  (let [mode-opts (get config mode)]
    (-> config
        (cond->
          mode-opts
          (deep-merge mode-opts))
        (dissoc :release :dev))))

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

    (when-not (find-ns target-ns)
      (try
        (require target-ns)
        (catch Exception e
          (throw (ex-info (format "failed to require :target %s for build %s" target build-id)
                   {:tag ::get-target-fn :target target} e)))))

    (let [fn (ns-resolve target-ns target-fn-sym)]
      (when-not fn
        (throw (ex-info (str "target-fn " target-fn-sym " not found") {:target target})))

      fn
      )))

(defn configure
  [build-state mode {:keys [build-id target] :as config}]
  {:pre [(build-api/build-state? build-state)
         (map? config)
         (keyword? mode)
         (keyword? build-id)
         (some? target)]
   :post [(build-api/build-state? %)]}

  (let [{:keys [build-options closure-defines compiler-options js-options] :as config}
        (config-merge config mode)

        target-fn
        (get-target-fn target build-id)]

    ;; must do this after calling get-target-fn
    ;; the namespace that it is in may have added to the multi-spec
    (when-not (s/valid? ::config/build+target config)
      (throw (ex-info "invalid build config" (assoc (s/explain-data ::config/build+target config)
                                               :tag ::config
                                               :config config))))

    (let [externs-file (io/file "externs" (str (name build-id) ".txt"))]

      (-> build-state
          (assoc
            :build-id build-id
            ::stage :init
            ::config config
            ::target-fn target-fn
            ::mode mode)
          ;; FIXME: not setting this for :release builds may cause errors
          ;; http://dev.clojure.org/jira/browse/CLJS-2002
          (update :runtime assoc :print-fn :console)
          (cond->
            ;; generic dev mode, each target can overwrite in :init stage
            (= :dev mode)
            (-> (build-api/enable-source-maps)
                (build-api/with-build-options
                  {:use-file-min false})
                (build-api/with-compiler-options
                  {:optimizations :none
                   :closure-defines {"goog.DEBUG" true}})
                (assoc :devtools (:devtools config)))

            ;; generic release mode
            (= :release mode)
            (-> (build-api/with-compiler-options
                  {:optimizations :advanced
                   :elide-asserts true
                   :pretty-print false
                   :closure-defines {"goog.DEBUG" false}}))

            closure-defines
            (build-api/with-compiler-options {:closure-defines closure-defines})

            compiler-options
            (build-api/with-compiler-options compiler-options)

            build-options
            (build-api/with-build-options build-options)

            (.exists externs-file)
            (assoc :externs-file externs-file)

            js-options
            (build-api/with-js-options js-options))

          ;; should do all configuration necessary
          (process-stage :configure true)

          ;; :optimizations is ignored in dev mode
          ;; but cljs-devtools still reads it from the options and complains
          ;; when it is not equal to :none
          ;; so we overwrite whatever configure did since dev/release configs are shared
          (cond->
            (= :dev mode)
            (assoc-in [:compiler-options :optimizations] :none))
          ))))


(defn compile
  [{::keys [mode] :as state}]
  {:pre [(build-api/build-state? state)]
   :post [(build-api/build-state? %)]}
  (if-not (modules/configured? state)
    ;; flat build, no modules
    (-> state
        (assoc ::build-info {})
        (process-stage :resolve false)
        (process-stage :compile-prepare true)
        (build-api/compile-sources)
        (update-build-info-after-compile)
        (process-stage :compile-finish true))
    ;; :modules based build
    (-> state
        (assoc ::build-info {})
        (process-stage :compile-prepare true)
        (modules/analyze)
        (update-build-info-from-modules)
        (build-api/compile-sources)
        (update-build-info-after-compile)
        (process-stage :compile-finish true))))

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
  (process-stage state :flush true))


