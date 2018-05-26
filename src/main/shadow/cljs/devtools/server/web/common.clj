(ns shadow.cljs.devtools.server.web.common
  (:require [shadow.server.assets :as assets]
            [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]
            [clojure.java.io :as io]
            [shadow.core-ext :as core-ext]))

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
   :body (core-ext/safe-pr-str data)})

(defn page-boilerplate
  [req {:keys [modules headers]} ^String content]
  {:status 200
   :headers (merge {"content-type" "text/html; charset=utf-8"} headers)
   :body
   (html5
     {:lang "en"}
     [:head
      ;; lol preload for local dev
      [:link {:as "script" :href "/js/shared.js" :rel "preload"}]
      [:title "shadow-cljs"]
      [:link {:rel "stylesheet" :href "/css/main.css"}]]
     [:body
      content
      [:script {:src "/js/shared.js" :defer true}]
      (for [x modules]
        [:script {:src (str "/js/" (name x) ".js") :defer true}])
      ])})

(defn nav []
  (html
    [:div
     [:a {:href "/explorer"} "home"]]))

(defn transit [{:keys [transit-str] :as req} obj]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str obj)})