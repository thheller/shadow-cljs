(ns shadow.test.runner
  (:require [shadow.test :as st]))

(defn start []
  (js/console.log "test env" @st/tests-ref)
  (st/run-all-tests))

(defn stop [done]
  ;; FIXME: determine if async tests are still running
  ;; and call done after instead
  ;; otherwise a live reload might interfere with running tests by
  ;; reloading code in the middle
  (done))

;; not sure we need to do something once?
(defn init []
  (start))
