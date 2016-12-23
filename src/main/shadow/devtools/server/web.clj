(ns shadow.devtools.server.web
  (:require [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]
            [shadow.server.assets :as assets]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.services.explorer :as explorer]
            [shadow.devtools.server.web.common :as common]
            [shadow.devtools.server.web.ws-devtools :as ws-devtools]
            [shadow.devtools.server.web.ws-ui :as ws-ui]
            ))

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

(defn explorer-page
  [{:keys [explorer] :as req}]
  (page-boilerplate
    req
    (html
      [:h1 "Explorer"]
      [:ul
       (for [provide
             (->> (explorer/get-project-tests explorer)
                  (sort))]
         [:li
          [:a {:href (str "/test-client/" provide)} (str provide)]])]
      )))

(defn test-client-page
  [{:keys [explorer assets] :as req} test-ns]
  (page-boilerplate
    req
    (html
      [:h1 "Test-Frame"]
      [:h2 test-ns]
      (assets/js-queue :none 'shadow.devtools.test/test-slave)
      )))

(defn root [req]
  (let [uri
        (get-in req [:ring-request :uri])]

    (cond
      (str/starts-with? uri "/ws/devtools")
      (ws-devtools/ws-start req)

      (str/starts-with? uri "/ws/ui")
      (ws-ui/ws-start req)

      (str/starts-with? uri "/test-client")
      (test-client-page req (symbol (subs uri 13)))

      (str/starts-with? uri "/explorer")
      (explorer-page req)

      :else
      (page-boilerplate req
        (html
          [:h1 "yo"]
          [:pre (pr-str (build/active-builds (:build req)))]
          [:div#log]
          (assets/js-queue :none 'shadow.devtools.ui/start)
          )))))
