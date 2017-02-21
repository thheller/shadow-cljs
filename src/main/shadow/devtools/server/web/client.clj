(ns shadow.devtools.server.web.client
  (:require [shadow.devtools.server.web.common :as common]
            [shadow.devtools.server.services.build :as build]
            [shadow.server.assets :as assets]
            [clojure.pprint :refer (pprint)]
            [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]))

(defn root [req proc-id]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body
   (html5
     {:lang "en"}
     [:head
      [:title "DEVTOOLS"]
      [:link {:rel "stylesheet" :href "/assets/client/css/main.css"}]]
     [:body
      [:div#app]
      (assets/js-queue :previous-sibling 'shadow.devtools.client.app/init {:proc-id proc-id})

      [:script {:src "/assets/client/js/main.js"}]])})
