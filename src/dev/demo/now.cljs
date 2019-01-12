(ns demo.now)

(defn handler [^js req ^js res]
  (.end res "Hello World"))
