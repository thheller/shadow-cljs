(ns shadow.devtools.server.compiler
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [clojure.spec :as s]
            [shadow.cljs.build :as cljs]
            [shadow.devtools.server.config :as config]))

(defn extract-build-info [state]
  (let [source->module
        (reduce
          (fn [index {:keys [sources name]}]
            (reduce
              (fn [index source]
                (assoc index source name))
              index
              sources))
          {}
          (:build-modules state))

        compiled-sources
        (into #{} (cljs/names-compiled-in-last-build state))

        build-sources
        (->> (:build-sources state)
             (map (fn [name]
                    (let [{:keys [js-name warnings] :as rc}
                          (get-in state [:sources name])]
                      {:name name
                       :js-name js-name
                       :module (get source->module name)})))
             (into []))]

    {:sources
     build-sources
     :compiled
     compiled-sources
     :warnings
     (cljs/extract-warnings state (:build-sources state))

     }))

(defn- update-build-info-from-modules
  [{:keys [build-modules] :as state}]
  (update state ::build-info merge {:modules build-modules}))

(defn- update-build-info-after-compile
  [state]
  (update state ::build-info merge (extract-build-info state)))

(defn process-stage
  [{::keys [config mode target-fn] :as state} stage optional?]
  (let [before
        (assoc state ::stage stage)

        after
        (target-fn before)]
    (if (and (not optional?) (identical? before after))
      (throw (ex-info "process didn't do anything on non-optional stage"
               {:stage stage :mode mode :config config}))
      after)))

(defn config-merge [config mode]
  ;; FIXME: merge nested maps, vectors
  (let [mode-opts
        (get config mode)]
    (merge config mode-opts)))

(defn get-target-fn [target]

  (let [target-fn-sym
        (cond
          (qualified-symbol? target)
          target

          ;; FIXME: maybe these shouldn't be as hard-coded
          (keyword? target)
          (case target
            :browser
            'shadow.devtools.targets.browser/process
            :node-script
            'shadow.devtools.targets.node-script/process
            :node-library
            'shadow.devtools.targets.node-library/process
            (throw (ex-info "invalid build target keyword, please use a symbol" {:target target})))

          :else
          (throw (ex-info (format "invalid target: %s" (pr-str target)) {:target target})))

        target-ns (-> target-fn-sym namespace symbol)]

    (when-not (find-ns target-ns)
      (try
        (require target-ns)
        (catch Exception e
          (throw (ex-info "failed to require build target-fn" {:target target} e)))))

    (let [fn (ns-resolve target-ns target-fn-sym)]
      (when-not fn
        (throw (ex-info (str "target-fn " target-fn-sym " not found") {:target target})))

      fn
      )))

(defn init
  ([mode config]
   (init (cljs/init-state) mode config))
  ([init-state mode {:keys [id target compiler-options] :as config}]
   {:pre [(cljs/compiler-state? init-state)
          (map? config)
          (keyword? mode)
          (keyword? id)
          (some? target)]
    :post [(cljs/compiler-state? %)]}

   (let [config
         (config-merge config mode)

         target-fn
         (get-target-fn target)]

     ;; must do this after calling get-target-fn
     ;; the namespace that is in may have added to the multi-spec
     (when-not (s/valid? ::config/build config)
       (throw (ex-info "invalid build config" {:config config})))

     (-> init-state
         (assoc :cache-dir (io/file "target" "shadow-cache" (name id) (name mode))
                ::stage :init
                ::config config
                ::target-fn target-fn
                ::mode mode)
         (cond->
           compiler-options
           (cljs/merge-compiler-options compiler-options)

           ;; generic dev mode, each target can overwrite in :init stage
           (= :dev mode)
           (-> (cljs/enable-source-maps)
               (cljs/merge-build-options
                 {:use-file-min false})
               (cljs/merge-compiler-options
                 {:optimizations :none}))

           ;; generic release mode
           (= :release mode)
           (cljs/merge-compiler-options
             {:optimizations :advanced
              :elide-asserts true
              :pretty-print false}))

         (process-stage :init false)
         (cljs/find-resources-in-classpath)
         ))))


(defn compile
  [{::keys [mode] :as state}]
  {:pre [(cljs/compiler-state? state)]
   :post [(cljs/compiler-state? %)]}

  (-> state
      (process-stage :compile-prepare true)
      (assoc ::build-info {})
      (cljs/prepare-compile)
      (cljs/prepare-modules)
      (update-build-info-from-modules)
      (cljs/do-compile-modules)
      (update-build-info-after-compile)
      (process-stage :compile-finish true)
      (cond->
        (= :release mode)
        (-> (process-stage :optimize-prepare true)
            (cljs/closure-optimize)
            (process-stage :optimize-finish true)
            ))))

(defn flush
  [state]
  {:pre [(cljs/compiler-state? state)]
   :post [(cljs/compiler-state? %)]}
  (process-stage state :flush true))


