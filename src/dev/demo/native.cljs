(ns demo.native
  (:require ["react" :as r :refer (thing)]))

;; lots of native interop, not actually valid code, just for testing externs generator

(thing)

(.test (r/xyz))
(.bar r)

(defn x [y]
  (.test y))

(defn wrap-baz [x]
  (.baz x))

(js/foo.bar.thing)

foo ;; warning, to prevent cache

