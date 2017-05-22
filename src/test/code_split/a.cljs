(ns code-split.a)

(js/console.log ::a)

(defn ^:export foo [x]
  (str "foo" x))

