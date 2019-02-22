(ns shadow.lazy
  (:require-macros [shadow.lazy])
  (:require
    [goog.async.Deferred]
    [goog.object :as gobj]
    [shadow.loader :as l]))


(defprotocol ILoadable
  (ready? [x]))

(deftype Loadable [modules deref-fn]
  ILoadable
  (ready? [this]
    (every? l/loaded? modules))

  IDeref
  (-deref [this]
    (when-not (ready? this)
      (throw (ex-info "loadable not ready yet" {:modules modules})))

    (deref-fn)))

(defn loadable [thing]) ;; macro

;; FIXME: maybe just replace all the goog.module.ModuleManager stuff
;; the API is just weird, can't control retries
;; why does it retry when all code is loaded but the eval failed?
;; FIXME: maybe don't expose the Thenable API, just take explicit args
;; would make it easier to port this to Clojure
(defn load
  ([^Loadable the-loadable]
   {:pre [(instance? Loadable the-loadable)]}
   (let [all-mods (.-modules the-loadable)

         loading-map
         (l/load-multiple (into-array (map name) all-mods))

         combined
         (js/goog.async.Deferred.)

         callback-fn
         (.-deref-fn the-loadable)

         err-fn
         (fn [err]
           (.errback combined err))

         success-fn
         (fn []
           (when (ready? the-loadable)
             (.callback combined (callback-fn))))]

     (doseq [mod all-mods]
       (let [^js mod-deferred (gobj/get loading-map (name mod))]
         (.addCallbacks mod-deferred success-fn err-fn)))

     combined)))
