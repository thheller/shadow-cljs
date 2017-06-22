(ns demo.mixed
  #?(:cljs (:require-macros [demo.mixed])))


;; CLJ cannot compile JS only code
(defn cljs-only []
  (js/console.log "cljs-only doesn't compile in CLJ"))

;; so it needs to put into a reader conditional
#?(:cljs
   (defn cljs-only []
     (js/console.log "now CLJ is happy")))

;; this will compile fine in CLJ and CLJS
;; so it does not need a reader conditional
(defn this-is-shared []
  :foo)

;; macros as CLJ only, so they also need to be inside  areader conditional
#?(:clj
   (defmacro hello-world [& args]
     :hello-world))

