(ns demo.http)

(defn loud-404s [req]
  {:status 404
   :body "NOT FOUND YO!"})

(defn proxy-predicate [ex]
  (tap> [:proxy-predicate ex])
  true)