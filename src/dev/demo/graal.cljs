(ns demo.graal)

(defn ^:export hello [foo]
  (str "Hello, " foo "!"))
