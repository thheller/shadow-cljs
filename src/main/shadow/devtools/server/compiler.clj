(ns shadow.devtools.server.compiler
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [shadow.cljs.build :as cljs]))

(defmulti process
  (fn [state]
    (::target state))
  :default ::noop)

(defmethod process ::noop [state]
  state)

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
  [{::keys [config mode target] :as state} stage optional?]
  (let [before
        (assoc state ::stage stage)

        after
        (process before)]
    (if (and (not optional?) (identical? before after))
      (throw (ex-info "process didn't do anything on non-optional stage"
               {:stage stage
                :mode mode
                :target target
                :config config}))
      after)))

(defn process-delegate
  "process the same stage of another target
   (for custom targets that just want to enhance one thing)"
  [{::keys [target] :as state} other-target]
  (-> state
      (assoc ::target other-target)
      (process)
      (assoc ::target target)))

(defn init
  ([mode config]
   (init (cljs/init-state) mode config))
  ([init-state mode {:keys [id target build-options] :as config}]
   {:pre [(cljs/compiler-state? init-state)
          (map? config)
          (keyword? mode)
          (keyword? id)
          (keyword? target)]
    :post [(cljs/compiler-state? %)]}

   (let [{:keys [public-dir public-path]}
         config]

     (-> init-state
         (assoc :cache-dir (io/file "target" "shadow-cache" (name id) (name mode))
                ::stage :init
                ::config config
                ::target target
                ::mode mode)
         (cond->
           build-options
           (cljs/set-build-options build-options)

           (= :dev mode)
           (-> (cljs/enable-source-maps)
               (cljs/set-build-options
                 {:optimizations :none
                  :use-file-min false}))

           (= :release mode)
           (cljs/set-build-options
             {:optimizations :advanced
              :pretty-print false}))

         (cond->
           public-dir
           (cljs/set-build-options
             {:public-dir (io/file public-dir)})
           public-path
           (cljs/set-build-options
             {:public-path public-path}))

         (process-stage :init false)
         (cljs/find-resources-in-classpath)))
    ))

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


