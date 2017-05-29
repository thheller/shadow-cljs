(ns code-split.a)

(js/console.log :foo :X ::a)

(defn ^:export foo [x]
  (str "foo" x))

