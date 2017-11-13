(ns demo.run-test)

(defn fn1 [& args]
  (prn [:fn1 args]))

(defn fn2 [& args]
  (prn [:fn2 args]))
