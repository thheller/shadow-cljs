(ns shadow.http.push-state
  (:require [clojure.java.io :as io]))

(defn handle [{:keys [http-root devtools] :as req}]
  (let [index-name
        (get devtools :push-state/index "index.html")]
    {:status 200
     :body (slurp (io/file http-root index-name))}))
