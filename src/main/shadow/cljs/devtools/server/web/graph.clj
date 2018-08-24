(ns shadow.cljs.devtools.server.web.graph
  (:require [shadow.cljs.devtools.graph :as graph]))

(defn serve [{:keys [transit-read transit-str] :as req}]
  (let [query
        (-> (get-in req [:ring-request :body])
            (transit-read))

        result
        (graph/parser req query)]

    {:status 200
     :header {"content-type" "application/transit+json"}
     :body (transit-str result)}
    ))
