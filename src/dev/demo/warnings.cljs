(ns demo.warnings)

(defn x [foo]
  foo)

i-dont-exist

(def x abc)

(+ "a" 1)

(have-some-more-warnings foo)
