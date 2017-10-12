(ns demo.http)

(defn loud-404s [req]
  {:status 404
   :body "NOT FOUND YO!"})
