(ns demo.macro)

(defmacro foo [& body]
  `(seq ~@body))
