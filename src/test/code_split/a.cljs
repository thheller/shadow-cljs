(ns code-split.a)

;; using defmulti for test purposes as it is never removed by closure
;; (defmulti foo :x)
;; (defmethod foo :a [_] :a)

(js/console.log ::a js/$.module)

(defn ^:export foo []
  "foo")

