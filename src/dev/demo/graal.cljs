(ns demo.graal)

(defn ^:export hello [foo]
  (str "Hello, " foo "!")
  (throw (ex-info "foo" {:foo foo})))
