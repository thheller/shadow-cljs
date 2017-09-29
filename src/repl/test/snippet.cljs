(ns test.snippet)

(set! *warn-on-infer* true)

(defn foo [x]
  (.bar x))

(list)

(js/React.createElement "foo")