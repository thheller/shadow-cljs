(ns code-split.a
  (:require [shadow.loader :as loader]))

(js/console.log :foo :X ::a)

(defn ^:export foo [x]
  (str "foo" x))

(defn test-fn []
  (-> (loader/load "c")
      (.then #(code-split.c/in-c "from-a"))))

(js/window.setTimeout test-fn 100)
