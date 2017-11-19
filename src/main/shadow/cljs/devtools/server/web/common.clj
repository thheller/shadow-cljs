(ns shadow.cljs.devtools.server.web.common
  (:require [shadow.server.assets :as assets]
            [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]
            [clojure.java.io :as io]))

(defn not-found
  ([req]
    (not-found req "Not found."))
  ([req msg]
   {:status 404
    :headers {"content-type" "text/plain"}
    :body msg}))

(defn unacceptable [_]
  {:status 406 ;; not-acceptable
   :headers {"Content-Type" "text/plain"}
   :body "websocket required"})

(defn edn [req data]
  {:status 200
   :header {"content-type" "application/edn"}
   :body (pr-str data)})

(defn page-boilerplate
  [req ^String content]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body
   (html5
     {:lang "en"}
     [:head
      ;; lol preload for local dev
      [:link {:as "script" :href "/js/ui.js" :rel "preload"}]
      [:title "shadow-cljs"]
      [:style "body { font-size: 12px; font-family: Menlo, monospace; padding: 10px; margin: 0;"]]
     [:body
      content
      [:script {:src "/js/ui.js" :defer true}]
      ])})

(defn nav []
  (html
    [:div
     [:a {:href "/explorer"} "home"]]))

(defn transit [{:keys [transit-str] :as req} obj]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str obj)})