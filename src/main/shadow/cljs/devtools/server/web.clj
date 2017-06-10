(ns shadow.cljs.devtools.server.web
  (:require
    [hiccup.core :refer (html)]
    [clojure.string :as str]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.explorer :as web-explorer]
    [shadow.cljs.devtools.server.web.api :as web-api]
    [shadow.server.assets :as assets]
    [shadow.cljs.devtools.server.worker.ws :as ws]))

(defn index-page [req]
  (common/page-boilerplate req
    (html
      [:h1 "shadow-cljs"]
      [:div#root "nothing useful to do here yet ... run \"shadow-cljs --interactive\""]
      ;; (assets/js-queue :none 'shadow.cljs.ui.app/init)
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

      (str/starts-with? uri "/worker")
      (ws/process req)

      :else
      common/not-found
      )))
