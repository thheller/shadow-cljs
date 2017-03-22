(ns shadow.cljs.devtools.server.web
  (:require
    [hiccup.core :refer (html)]
    [clojure.string :as str]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.explorer :as web-explorer]
    [shadow.cljs.devtools.server.web.api :as web-api]))

(defn index-page [req]
  (common/page-boilerplate req
    (html
      [:h1 "Welcome."]
      [:a {:href "/explorer/"} "Explore CLJS"]
      )))

(defn root [{:keys [build] :as req}]
  (let [uri
        (get-in req [:ring-request :uri])]

    (cond
      (= uri "/")
      (index-page req)

      (str/starts-with? uri "/api")
      (web-api/root req)

      (str/starts-with? uri "/explorer")
      (web-explorer/root req)

      :else
      common/not-found
      )))
