(ns shadow.devtools.test
  (:require-macros [shadow.devtools.test :as m])
  (:require [cljs.test :as test]
            [shadow.api :refer (ns-ready)]
            [fipp.clojure :refer (pprint)]
            [shadow.util :refer (log)]
            [shadow.dom :as dom]
            [clojure.string :as str]))

(def runner-ns "shadow.devtools.livetest.runner")

(defmethod test/report :default [{:keys [type] :as m}]
  (js/console.log "reporter" type m))

(defmethod test/report [::report :pass] [m]
  (test/inc-report-counter! :pass))

(defmethod test/report [::report :error] [m]
  (js/console.error "ERROR in" (test/testing-vars-str m) (:actual m))
  (test/inc-report-counter! :error)
  (js/console.log "\nERROR in" (test/testing-vars-str m))
  (when (seq (:testing-contexts (test/get-current-env)))
    (js/console.log (test/testing-contexts-str)))
  (when-let [message (:message m)] (js/console.log message))
  (test/print-comparison m))

(defn- print-comparison [{:keys [expected actual] :as m}]
  (js/console.log "%cexpected:" "color: red;")
  (js/console.log (with-out-str (pprint expected)))
  (js/console.log "%cactual" "color: red;" )
  (js/console.log (with-out-str (pprint actual))))

(def success-ref (volatile! false))

(defmethod test/report [::report :fail] [m]
  (vreset! success-ref false)
  (test/inc-report-counter! :fail)
  (js/console.group "%cFAILURE" "color: red;")
  (when-let [message (:message m)]
    (js/console.log message))
  (print-comparison m)
  (js/console.groupEnd "%cFAILURE"))

(defmethod test/report [::report :summary] [m]
  (js/console.log (str "Ran " (:test m) " tests containing " (+ (:pass m) (:fail m) (:error m)) " assertions."))
  (js/console.log (str (:fail m) " failures, " (:error m) " errors.")))

(defmethod test/report [::report :begin-test-ns] [m]
  (js/console.group (str "test-ns: " (name (:ns m)))))

(defmethod test/report [::report :end-test-ns] [m]
  (js/console.groupEnd (str "test-ns: " (name (:ns m)))))

(defmethod test/report [::report :begin-test-var] [m])
(defmethod test/report [::report :end-test-var] [m])
(defmethod test/report [::report :end-run-tests] [m])
(defmethod test/report [::report :end-test-all-vars] [m])
(defmethod test/report [::report :end-test-vars] [m])

(defmethod test/report [::report :begin-test-var]
  [{:keys [var]}]
  (vreset! success-ref true)
  (js/console.group (str "test-var: " (-> var meta :name str))))

(defn var->fqn [var]
  (let [{var-ns :ns
         var-name :name}
        (meta var)]
    (str var-ns "/" var-name)))

(defmethod test/report [::report :end-test-var]
  [{:keys [var]}]
  (let [fqn (var->fqn var)

        success?
        @success-ref

        container
        (dom/by-id fqn)]

    (dom/add-class container (if success? "shadow-test-pass" "shadow-test-fail")))
  (js/console.groupEnd (str "test-var: " (-> var meta :name str))))

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
  (-> (test/empty-env)
      (assoc :reporter ::report)))

(defn livetest-start []
  (let [start-fn
        (-> (js/goog.getObjectByName runner-ns)
            (js/goog.object.get "livetest"))]
    (start-fn)))

(defn livetest []
  (when-let [toolbar (dom/query-one ".shadow-test-toolbar")]
    (-> toolbar
        (dom/append [:button "re-test"])
        (dom/on :click
          (fn [e]
            (dom/ev-stop e)
            (livetest-stop)
            (livetest-start)
            ))))

  (js/console.info "LIVETEST!")
  ;; wait a bit to let the REPL and other stuff init properly first
  (js/setTimeout #(livetest-start) 500))

(defn dom-test-el [container el-name]
  (dom/append container [:div.shadow-test-el-label el-name])
  (dom/append container [:div.shadow-test-el {:data-test-el el-name}]))

(defn dom-test-container [ns test]
  (let [fqn (str ns "/" test)]
    (dom/append
      (dom-test-root)
      [:div.shadow-test-container {:id fqn}
       [:div.shadow-test-container-label fqn]])))

(defn dom? []
  (not (nil? (dom-test-root))))

(ns-ready)
