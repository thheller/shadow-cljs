(ns demo.type)

(deftype Foo [a b]
  Object
  (foo [this x]
    x))
