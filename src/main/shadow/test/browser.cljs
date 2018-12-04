(ns shadow.test.browser
  "generic browser test runner"
  (:require
    [shadow.test :as st]
    [shadow.dom :as dom]
    [cljs-test-display.core :as ctd]))

(defn start []
  (st/run-all-tests (ctd/init! "test-root")))

(defn stop [done]
  ;; FIXME: determine if async tests are still pending
  (done))

;; not sure we need to do something once?
(defn ^:export init []
  (dom/append [:div#test-root])
  (start))
