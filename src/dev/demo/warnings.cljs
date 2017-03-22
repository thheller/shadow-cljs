(ns demo.warnings)

(defn x [foo]
  foo)

#_ i-dont-exist

#_ (def x abc)

#_ (+ "a" 1)

#_ (have-some-more-warnings foo)
