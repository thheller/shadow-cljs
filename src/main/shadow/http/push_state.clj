(ns shadow.http.push-state
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

(defn handle [{:keys [uri http-roots http-config] :as req}]
  (let [accept (get-in req [:headers "accept"])]
    (if (and accept (not (str/includes? accept "text/html")))
      not-found
      (let [index-name
            (get http-config :push-state/index "index.html")

            headers
            (get http-config :push-state/headers {"content-type" "text/html; charset=utf-8"})

            index-file
            (reduce
              (fn [_ http-root]
                (let [file (io/file http-root index-name)]
                  (when (and file (.exists file))
                    (reduced file))))
              nil
              http-roots)]
        
        (if-not index-file
          ;; FIXME: serve some kind of default page instead
          (assoc not-found :body "Not found. Missing index.html.")
          {:status 200
           :headers headers
           :body (slurp index-file)})))))
