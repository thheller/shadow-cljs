(ns shadow.test.node
  {:dev/always true}
  (:require
    [shadow.test.env :as env]
    [cljs.test :as ct]
    [shadow.test :as st]))

;; FIXME: add option to not exit the node process?
(defmethod ct/report [::ct/default :end-run-tests] [m]
  (if (ct/successful? m)
    (js/process.exit 0)
    (js/process.exit 1)))

(defn main []
  (-> (env/get-test-data)
      (env/reset-test-data!))
  (st/run-all-tests))
