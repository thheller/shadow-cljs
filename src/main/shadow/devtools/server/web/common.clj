(ns shadow.devtools.server.web.common
  (:require [shadow.server.assets :as assets]
            [hiccup.page :refer (html5)]))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

(defn unacceptable [_]
  {:status 406 ;; not-acceptable
   :headers {"Content-Type" "text/plain"}
   :body "websocket required"})

(defn page-boilerplate
  [{:keys [assets] :as req} ^String content]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body
   (html5
     {:lang "en"}
     [:head
      [:title "hello world"]
      (assets/html-head assets ["ui"])]
     [:body
      content
      (assets/html-body assets ["main"])
      ])})

(defn transit [{:keys [transit-str] :as req} obj]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str obj)}
  )