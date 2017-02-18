(ns shadow.devtools.server.web
  (:require
    [hiccup.core :refer (html)]
    [shadow.server.assets :as assets]
    [clojure.string :as str]
    [clojure.pprint :refer (pprint)]
    [shadow.devtools.server.services.build :as build]
    [shadow.devtools.server.services.explorer :as explorer]
    [shadow.devtools.server.web.common :as common]
    [shadow.devtools.server.web.ws-client :as ws-client]
    [shadow.devtools.server.web.ws-frontend :as ws-frontend]
    [shadow.devtools.server.web.explorer :as web-explorer]
    [shadow.devtools.server.web.client :as web-client]
    [shadow.devtools.server.web.api :as web-api]
    [clojure.tools.logging :as log])
  (:import (java.util UUID)))

(defn index-page [req]
  (common/page-boilerplate req
    (html
      #_ [:ul
       (for [build-id (build/active-builds (:build req))]
         [:li [:a {:href (str "/client/" build-id)} (str build-id)]])]

      [:div#app]
      (assets/js-queue :none 'shadow.devtools.frontend.app/start)
      )))

(defn root [{:keys [build] :as req}]
  (let [uri
        (get-in req [:ring-request :uri])]

    (cond
      (= uri "/")
      (index-page req)

      (str/starts-with? uri "/api")
      (web-api/root req)

      (str/starts-with? uri "/ws/client")
      (ws-client/ws-start req)

      (str/starts-with? uri "/ws/frontend")
      (ws-frontend/ws-start req)

      ;; only for debugging purposes
      (= uri "/self-connect")
      (let [{:keys [proc-id]}
            (build/find-proc-by-build-id build :self)]
        (web-client/root
          req
          proc-id))

      (str/starts-with? uri "/client/")
      (web-client/root req
        (-> uri
            (subs (count "/client/"))
            (UUID/fromString)))

      (str/starts-with? uri "/explorer")
      (web-explorer/root req)

      :else
      common/not-found
      )))
