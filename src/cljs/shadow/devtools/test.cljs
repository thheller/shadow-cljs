(ns shadow.devtools.test
  (:require-macros [shadow.devtools.test :as m])
  (:require [cljs.test :as test]
            [shadow.util :refer (log)]
            [shadow.dom :as dom]))

(def ns-to-test "shadow.devtools.livetest.runner")

(defmethod test/report [::test/default :error] [m]
  (js/console.error "ERROR in" (test/testing-vars-str m) (:actual m))
  (test/inc-report-counter! :error)
  (println "\nERROR in" (test/testing-vars-str m))
  (when (seq (:testing-contexts (test/get-current-env)))
    (println (test/testing-contexts-str)))
  (when-let [message (:message m)] (println message))
  (test/print-comparison m))

(defn dom-test-root []
  (dom/by-id "app"))

(defn dom-test-clear []
  (dom/reset (dom-test-root)))

(defn default-stop []
  (dom-test-clear)
  ;; (js/console.clear)
  )

(defn empty-env []
  (test/empty-env))

(defn livetest-stop []
  (let [stop-fn
        (-> (js/goog.getObjectByName ns-to-test)
            (js/goog.object.get "stop"))]
    (if stop-fn
      (stop-fn)
      (default-stop))))

(defn livetest-start []
  (let [start-fn
        (-> (js/goog.getObjectByName ns-to-test)
            (js/goog.object.get "livetest"))]
    (if-not start-fn
      (js/console.warn ns-to-test "did not define a livetest fn")
      (start-fn))))

(defn livetest []
  (let [x (dom/insert-before (dom/by-id "app") [:button "re-test"])]
    (dom/on x :click
      (fn [e]
        (dom/ev-stop e)
        (livetest-stop)
        (livetest-start)
        )))

  (js/console.info "LIVETEST!")
  (livetest-start))

(defn dom-test-el [label]
  (dom/append (dom-test-root) [:h1 label])
  (dom/append (dom-test-root) [:div.test-el]))

(defn dom? []
  (not (nil? (dom-test-root))))

