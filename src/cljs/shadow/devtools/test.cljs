(ns shadow.devtools.test
  (:require-macros [shadow.devtools.test :as m])
  (:require [cljs.test :as test]
            [shadow.util :refer (log)]
            [shadow.dom :as dom]))

(def runner-ns "shadow.devtools.livetest.runner")

(defmethod test/report [::test/default :error] [m]
  (js/console.error "ERROR in" (test/testing-vars-str m) (:actual m))
  (test/inc-report-counter! :error)
  (println "\nERROR in" (test/testing-vars-str m))
  (when (seq (:testing-contexts (test/get-current-env)))
    (println (test/testing-contexts-str)))
  (when-let [message (:message m)] (println message))
  (test/print-comparison m))

(defmethod test/report [::test/default :begin-test-var]
  [{:keys [var]}]
  (js/console.group (-> var meta :name str)))

(defmethod test/report [::test/default :end-test-var]
  [{:keys [var]}]
  (js/console.groupEnd (-> var meta :name str)))

(defn on-reset! [node callback]
  (js/goog.object.set node "__shadow$remove" callback))

(defn dom-test-root []
  (dom/by-id "shadow-test-root"))

(defn dom-test-clear []
  (let [root
        (dom-test-root)]

    (doseq [container (dom/query "div.shadow-test-container" (dom-test-root))
            :let [shadow-remove (js/goog.object.get container "__shadow$remove")]
            :when shadow-remove]
      (shadow-remove container))

    (dom/reset root)))

(defn livetest-stop []
  (dom-test-clear)
  (js/console.clear))

(defn empty-env []
  (test/empty-env))

(defn livetest-start []
  (let [start-fn
        (-> (js/goog.getObjectByName runner-ns)
            (js/goog.object.get "livetest"))]
    (start-fn)))

(defn livetest []
  (let [x (dom/insert-before (dom-test-root) [:button "re-test"])]
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
  (dom/append (dom-test-root) [:div.shadow-test-container]))

(defn dom? []
  (not (nil? (dom-test-root))))

