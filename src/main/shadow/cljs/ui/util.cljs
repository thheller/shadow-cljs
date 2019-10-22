(ns shadow.cljs.ui.util
  (:require
    [goog.object :as gobj]
    [cljs.pprint :refer (pprint)]
    [cognitect.transit :as transit]
    [shadow.markup.react :as html]
    [com.fulcrologic.fulcro.components :as fc]))

(defn gen-id []
  (str (random-uuid)))

;; dirty dirty mutable state, do not look at me!
;; sometimes need some component local state which isn't relevant to render
(defn get-local! [comp]
  (or (gobj/get comp "shadow$local")
      {}))

(defn swap-local! [comp update-fn & args]
  (let [vals (get-local! comp)
        new-vals (apply update-fn vals args)]
    (gobj/set comp "shadow$local" new-vals)
    new-vals))

;; constructs a callback fn once per component
;; useful for :ref and event handlers
;; taking (defn the-callback [component <the-args> & other-args])
;; :onClick (comp-fn this 1 2 3)
;; would call
;; (the-callback this <e> 1 2 3)
(defn comp-fn [instance id callback-fn & callback-args]
  (let [fn-id [::fn id]

        fn-ref
        (-> (get-local! instance)
            (get fn-id))]

    ;; if the fn is already created for the component just update args for when
    ;; it is actually getting called
    (if fn-ref
      (do (swap-local! instance update fn-id assoc :callback-args callback-args :callback-fn callback-fn)
          (:mem-fn fn-ref))
      (let [mem-fn
            (fn [arg] ;; FIXME: support varargs here?
              (let [{:keys [callback-fn callback-args]}
                    (-> (get-local! instance)
                        (get fn-id))]

                (apply callback-fn instance arg callback-args)
                ;; FIXME: this is special behaviour for :ref
                ;; might not play nice with other callbacks?
                ;; maybe check name of :id for `-ref` eg. ::foo-ref
                ;; removal is not super critical since the component
                ;; will usually be GC'd anyways and real cleanup
                ;; should be done in componentWillUnmount
                (when (nil? arg)
                  (swap-local! instance dissoc fn-id))))]

        (swap-local! instance assoc fn-id {:mem-fn mem-fn
                                           :callback-args callback-args
                                           :callback-fn callback-fn})
        mem-fn))))

;; FIXME: I have created these functions a billion times, finally put them somewhere reusable

(defn conj-vec [x y]
  (if (nil? x) [y] (conj x y)))

(defn conj-set [x y]
  (if (nil? x) #{y} (conj x y)))

(defn dump [obj]
  (html/pre
    (with-out-str
      (pprint obj))))

(defn transit-read [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn transit-str [msg]
  (let [w (transit/writer :json)]
    (transit/write w msg)))


