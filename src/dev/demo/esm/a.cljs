(ns demo.esm.a
  (:require
    ["https://cdn.pika.dev/preact@^10.0.0" :as x]))

(def foo "demo.esm.a/foo")

(def bar "demo.esm.a/bar")

(defn ^:dev/after-load init []
  (js/console.log "init from esm.a" x foo))

