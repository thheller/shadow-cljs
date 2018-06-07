(ns demo.azure.simple
  (:require ["react-dom/server" :as rdom]))

(defn test-fn
  {:azure/disabled false
   :azure/bindings
   [{:authLevel "function"
     :type "httpTrigger"
     :direction "in"
     :name "req"}
    {:type "http"
     :direction "out"
     :name "$return"}]}
  [^js context ^js req]
  (.. context (log "JavaScript HTTP trigger function processed a request."))

  (let [name (or (.. req -query -name)
                 (and (.. req -body) (.. req -body -name)))

        result
        (if (seq name)
          {:status 200
           :body (str "CLJS: Hello " name)}
          {:status 400
           :body "Please pass a name on the query string or in the request body"})]

    (.. context (done nil (clj->js result)))))
