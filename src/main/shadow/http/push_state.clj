(ns shadow.http.push-state
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

(defn handle [{:keys [uri http-root devtools] :as req}]
  (let [accept (get-in req [:headers "accept"])]
    (if (and accept (not (str/includes? accept "text/html")))
      not-found
      (let [index-name
            (get devtools :push-state/index "index.html")

            headers
            (get devtools :push-state/headers {"content-type" "text/html; charset=utf-8"})

            index-file
            (io/file http-root index-name)]
        (if-not (.exists index-file)
          not-found
          {:status 200
           :headers headers
           :body (slurp index-file)})))))
