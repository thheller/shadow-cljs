(ns shadow.cljs.devtools.server.web.common
  (:require [shadow.server.assets :as assets]
            [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]
            [clojure.java.io :as io]))

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

(defn unacceptable [_]
  {:status 406 ;; not-acceptable
   :headers {"Content-Type" "text/plain"}
   :body "websocket required"})

(defn page-boilerplate
  [req ^String content]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body
   (html5

     {:lang "en"}
     [:head
      ;; lol preload for local dev
      [:link {:as "script" :href "/js/bundle.js" :rel "preload"}]
      [:link {:as "script" :href "/js/ui.js" :rel "preload"}]
      [:title "shadow-cljs"]
      [:style "body { font-size: 12px; font-family: Menlo, monospace; padding: 20px;}"]]
     [:body
      content
      [:script {:src "/js/bundle.js"}]
      [:script {:src "/js/ui.js"}]
      ])})

(defn nav []
  (html
    [:div
     [:a {:href "/"} "home"]]))

(defn transit [{:keys [transit-str] :as req} obj]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str obj)}
  )