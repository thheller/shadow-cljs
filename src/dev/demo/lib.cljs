(ns demo.lib)

(defn hello []
  (js/console.log js/goog.global.CLOSURE_DEFINES)
  "hello")

