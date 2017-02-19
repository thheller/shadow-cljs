(ns shadow.devtools.server.compiler.script
  (:require [shadow.devtools.server.compiler.protocols :as p]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]))

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
          (node/configure config))))

  (compile-pre [_ state]
    state)

  (compile-post [_ state]
    (cljs/closure-optimize state))

  (compile-flush [_ state]
    (node/flush-optimized state)))

(deftype Dev [config]
  p/ICompile
  (compile-init [_ state]
    (let [{:keys [dev]}
          config]

      (-> state
          (cljs/enable-source-maps)
          (cljs/set-build-options
            {:optimizations :none
             :use-file-min false})
          (cond->
            dev
            (cljs/set-build-options dev))
          (node/configure config)
          )))

  (compile-pre [_ state]
    state)

  (compile-post [_ state]
    state)

  (compile-flush [_ state]
    (node/flush-unoptimized state)))

(defmethod p/make-compiler :script
  [config mode]
  (case mode
    :dev
    (Dev. config)
    :release
    (Release. config)))
