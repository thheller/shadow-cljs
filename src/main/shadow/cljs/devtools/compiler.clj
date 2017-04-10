(ns shadow.cljs.devtools.compiler
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [clojure.spec :as s]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.cljs-specs]))

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

(defn get-target-fn [target]

  (let [target-fn-sym
        (cond
          (qualified-symbol? target)
          target

          ;; FIXME: maybe these shouldn't be as hard-coded
          (keyword? target)
          (case target
            :browser
            'shadow.cljs.devtools.targets.browser/process
            :node-script
            'shadow.cljs.devtools.targets.node-script/process
            :node-library
            'shadow.cljs.devtools.targets.node-library/process
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
  ([init-state mode {:keys [id target] :as config}]
   {:pre [(cljs/compiler-state? init-state)
          (map? config)
          (keyword? mode)
          (keyword? id)
          (some? target)]
    :post [(cljs/compiler-state? %)]}

   (let [{:keys [build-options closure-defines compiler-options] :as config}
         (config-merge config mode)

         target-fn
         (get-target-fn target)]

     ;; must do this after calling get-target-fn
     ;; the namespace that is in may have added to the multi-spec
     (when-not (s/valid? ::config/build+target config)
       (throw (ex-info "invalid build config" (assoc (s/explain-data ::config/build+target config)
                                                     :tag ::config
                                                     :config config))))

     (-> init-state
         (assoc :cache-dir (io/file "target" "shadow-cache" (name id) (name mode))
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
           (-> (cljs/enable-source-maps)
               (cljs/merge-build-options
                 {:use-file-min false
                  :closure-defines {"goog.DEBUG" true}})
               (cljs/merge-compiler-options
                 {:optimizations :none})

               )

           ;; generic release mode
           (= :release mode)
           (cljs/merge-compiler-options
             {:optimizations :advanced
              :elide-asserts true
              :pretty-print false})

           closure-defines
           (cljs/merge-build-options {:closure-defines closure-defines})

           compiler-options
           (cljs/merge-compiler-options compiler-options)

           ;; FIXME: CAREFUL WITH THESE, may destroy everything
           ;; run them through some kind of spec at least
           build-options
           (cljs/merge-build-options build-options))

         (cljs/find-resources-in-classpath)
         (process-stage :init false)))))


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
      (cljs/compile-modules*)
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


