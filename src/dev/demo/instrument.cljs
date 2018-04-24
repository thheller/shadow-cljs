(ns ^:dev/always demo.instrument
  (:require
    [demo.browser :as x]
    [cljs.spec.test.alpha :as st]))

(defn ^:dev/after-load instrument []
  (st/instrument)
  (js/console.log "instrument called")
  (x/bla 1)
  (x/bla "foo"))
