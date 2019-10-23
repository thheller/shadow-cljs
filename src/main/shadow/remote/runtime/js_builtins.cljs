(ns shadow.remote.runtime.js-builtins
  (:require
    [goog.object :as gobj]
    [clojure.core.protocols :as p]))

(extend-protocol p/Datafiable
  ;; FIXME: this is kind of a bad idea
  ;; can't do this for all objects, since none of the CLJS types implement this
  ;; protocol either. the protocol dispatch will end up using object
  ;; FIXME: this could detect CLJS types to some extent
  ;; or should it just implement the protocols for the types?
  object
  (datafy [o]
    (if-not (identical? (.-__proto__ o) js/Object.prototype)
      o
      (with-meta
        (->> (gobj/getKeys o)
             (reduce
               (fn [m key]
                 (assoc! m key (gobj/get o key)))
               (transient {}))
             (persistent!))

        {`p/nav (fn [coll k v]
                  (gobj/get o k))})))

  array
  (datafy [o]
    (vec o)))
