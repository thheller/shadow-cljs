(ns shadow.cljs.ui.worker-preload
  (:require [shadow.cljs.ui.worker.env :as env]))

;; timing problem with tap> maybe not being initialized yet

(js/setTimeout
  (fn []
    (tap> env/app-ref)
    (dotimes [x 10]
      (tap> {:x x})))
  500)
