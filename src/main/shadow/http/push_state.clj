(ns shadow.http.push-state
  (:require [clojure.java.io :as io]))

(defn handle [{:keys [http-root devtools] :as req}]
  (let [index-name
        (get devtools :push-state/index "index.html")

        headers
        (get devtools :push-state/headers {"content-type" "text/html; charset=utf-8"})]
    {:status 200
     :headers headers
     :body (slurp (io/file http-root index-name))}))
