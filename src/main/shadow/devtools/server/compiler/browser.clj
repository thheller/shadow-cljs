(ns shadow.devtools.server.compiler.browser
  (:require [shadow.devtools.server.compiler.protocols :as p]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]))

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

(defn- configure [state config]
  (let [{:keys [public-path public-dir modules] :as config}
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

(deftype Release
  [config]
  p/ICompile
  (compile-init [_ state]
    (let [{:keys [release]} config]
      (-> state
          (cljs/set-build-options
            {:optimizations :simple
             :pretty-print false})
          (cond->
            release
            (cljs/set-build-options release))
          (configure config))))

  (compile-pre [_ state]
    state)

  (compile-post [_ state]
    (cljs/closure-optimize state))

  (compile-flush [_ state]
    (cljs/flush-modules-to-disk state)))

(deftype Dev [config]
  p/ICompile
  (compile-init [_ state]
    (let [{:keys [dev]} config]
      (-> state
          (cljs/enable-source-maps)
          (cljs/set-build-options
            {:optimizations :none
             :use-file-min false})
          (cond->
            dev
            (cljs/set-build-options dev))
          (configure config)
          )))

  (compile-pre [_ state]
    state)

  (compile-post [_ state]
    state)

  (compile-flush [_ state]
    (cljs/flush-unoptimized state)))

(defmethod p/make-compiler :script
  [config mode]
  (case mode
    :dev
    (Dev. config)
    :release
    (Release. config)))
