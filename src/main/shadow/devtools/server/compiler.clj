(ns shadow.devtools.server.compiler
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.cljs.umd :as umd]))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries depends-on module-config))
    state
    modules))

(defn- trigger-event [state event]
  (prn [:trigger-event event])
  state)

(defmulti configure (fn [state] (get-in state [::config :target])))

(defmethod configure :browser [state]
  (let [config
        (::config state)

        {:keys [public-path public-dir modules] :as config}
        (merge default-browser-config config)]
    (-> state
        (cond->
          public-dir
          (cljs/set-build-options
            {:public-dir (io/file public-dir)})
          public-path
          (cljs/set-build-options
            {:public-path public-path}))
        (configure-modules modules)
        )))

(defmethod configure :script [state]
  (let [{:keys [output-to main] :as config}
        (::config state)]
    (-> state
        (node/configure config))))

(defmethod configure :library [state]
  (let [{:keys [exports] :as config}
        (::config state)]
    (-> state
        (umd/create-module exports config)
        )))

(defn init
  ([mode config]
    (init mode config {}))
  ([mode {:keys [dev release target] :as config} init-options]
   {:pre [(contains? #{:dev :release} mode)
          (map? config)]}
   (-> (cljs/init-state)
       (assoc ::mode mode
              ::config config)
       (merge init-options)
       (cljs/set-build-options
         (case mode
           :dev
           {:optimizations :none
            :use-file-min false}
           :release
           {:optimizations :advanced
            :pretty-print false}))
       (trigger-event :before-init)
       (cond->
         dev
         (cljs/enable-source-maps)

         (and dev (= :dev mode))
         (cljs/set-build-options dev)

         (and release (= :release mode))
         (cljs/set-build-options release))

       (cljs/find-resources-in-classpath)
       (configure)
       (trigger-event :after-init)
       )))

(defn prepare [state]
  state)

(defn- update-build-info-from-modules
  [{:keys [build-modules] :as state}]
  (update state ::build-info merge {:modules build-modules}))

(defn- update-build-info-after-compile
  [{:keys [build-sources] :as state}]
  (reduce
    (fn [state source-name]
      (let [{:keys [type cached] :as src}
            (get-in state [:sources source-name])]
        (cond
          (and (= type :cljs) cached)
          (update-in state [::build-info :cached] conj source-name)
          (and (= type :cljs) (not cached))
          (update-in state [::build-info :compiled] conj source-name)
          :else
          state
          )))
    state
    build-sources))

(defn dummy [state]
  (pprint (::build-info state))
  state)

(defn compile [state]
  (-> state
      (assoc ::build-info {:compiled []
                           :cached []})
      (trigger-event :before-compile)
      (cljs/prepare-compile)
      (cljs/prepare-modules)
      (update-build-info-from-modules)
      (cljs/do-compile-modules)
      (update-build-info-after-compile)
      (trigger-event :after-compile)
      (cond->
        (= :release (::mode state))
        (-> (trigger-event :before-optimize)
            (cljs/closure-optimize)
            (trigger-event :after-optimize)))

      (dummy)
      ))

(defmulti flush*
  (fn [state]
    [(::mode state)
     (get-in state [::config :target])]))

(defmethod flush* [:dev :browser]
  [state]
  (-> state
      (cljs/flush-unoptimized)))

(defmethod flush* [:release :browser]
  [state]
  (-> state
      (cljs/flush-modules-to-disk)))

(defmethod flush* [:dev :script]
  [state]
  (-> state
      (node/flush-unoptimized)))

(defmethod flush* [:release :script]
  [state]
  (-> state
      (node/flush)))

(defmethod flush* [:dev :library]
  [state]
  (-> state
      (umd/flush-unoptimized-module)))

(defmethod flush* [:release :library]
  [state]
  (-> state
      (umd/flush-module)))

(defn flush [state]
  (-> state
      (trigger-event :before-flush)
      (flush*)
      (trigger-event :after-flush)))


