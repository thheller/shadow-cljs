(ns test.runnable)

(defn foo []
  (prn :foo))

(defn foo-server
  {:shadow/requires-server true}
  [& args]
  (prn [:foo-server args]))