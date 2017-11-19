(ns shadow.cljs.devtools.server.web
  (:require
    [clojure.string :as str]
    [hiccup.core :refer (html)]
    [shadow.http.router :as http]
    [shadow.server.assets :as assets]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.api :as web-api]
    [shadow.cljs.devtools.server.worker.ws :as ws]))

(defn index-page [{:keys [dev-http] :as req}]
  (common/page-boilerplate req
    (html
      [:h1 "shadow-cljs"]
      [:h2 "HTTP Servers"]
      [:ul
       (for [{:keys [build-id port ssl] :as srv} (:servers dev-http)]
         (let [url (str "http" (when ssl "s") "://localhost:" port)]
           [:li [:a {:href url} (str url " - " (pr-str build-id))]]))]

      ;; (assets/js-queue :none 'shadow.cljs.ui.app/init)
      )))

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:ANY "^/api" web-api/root)
    (:ANY "^/worker" ws/process)
    common/not-found))
