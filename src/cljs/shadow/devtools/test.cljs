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

(def reset-callback-ref (volatile! nil))

(defn on-reset!
  ([callback]
   (vreset! reset-callback-ref callback))
  ([node callback]
   (js/goog.object.set node "__shadow$remove" callback)))

(defn dom-test-root []
  (dom/by-id "shadow-test-root"))

(defn dom-test-clear []
  (let [root
        (dom-test-root)]

    (doseq [test-el (dom/query "div.shadow-test-el" (dom-test-root))
            :let [shadow-remove
                  (or (js/goog.object.get test-el "__shadow$remove")
                      @reset-callback-ref)]
            :when shadow-remove]
      (shadow-remove test-el))

    (dom/reset root)))

(defn livetest-stop []
  (js/console.clear)
  (js/console.group "livetest-reset")
  (dom-test-clear)
  (js/console.groupEnd "livetest-reset"))

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

(defn dom-test-el [container dom-id]
  (dom/append container [:h2 dom-id])
  (dom/append container [:div.shadow-test-el {:data-id dom-id}]))

(defn dom-test-container [label]
  (dom/append
    (dom-test-root)
    [:div.shadow-test-container
     [:h1 label]]))

(defn dom? []
  (not (nil? (dom-test-root))))

