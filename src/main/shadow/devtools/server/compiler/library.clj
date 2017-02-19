(ns shadow.devtools.server.compiler.library
  (:require [shadow.devtools.server.compiler.protocols :as p]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.umd :as umd]))

(deftype Release
  [config]
  p/ICompile
  (compile-init [_ state]
    (-> state
        (cljs/set-build-options
          {:optimizations :simple
           :pretty-print false})))

  (compile-pre [_ state]
    state)

  (compile-post [_ state]
    (cljs/closure-optimize state))

  (compile-flush [_ state]
    (umd/flush-module state)))

(deftype Dev [config]
  p/ICompile
  (compile-init [_ state]
    (let [{:keys [exports dev]}
          config]

      (-> state
          (cljs/enable-source-maps)
          (cljs/set-build-options
            {:optimizations :none
             :use-file-min false})
          (cond->
            dev
            (cljs/set-build-options dev))
          (umd/create-module exports config))))

  (compile-pre [_ state]
    state)

  (compile-post [_ state]
    state)

  (compile-flush [_ state]
    (umd/flush-unoptimized-module state)))


(defmethod p/make-compiler :library
  [config mode]
  (case mode
    :dev
    (Dev. config)
    :release
    (Release. config)))
