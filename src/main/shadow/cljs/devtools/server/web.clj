(ns shadow.cljs.devtools.server.web
  (:require
    [clojure.string :as str]
    [hiccup.core :refer (html)]
    [shadow.http.router :as http]
    [shadow.server.assets :as assets]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.api :as web-api]
    [shadow.cljs.devtools.server.web.release-snapshots :as release-snapshots]
    [shadow.cljs.devtools.server.worker.ws :as ws]
    [clojure.java.io :as io]
    [clojure.edn :as edn]))

(defn index-page [{:keys [dev-http] :as req}]
  (common/page-boilerplate req
    (html
      [:h1 "shadow-cljs"]
      [:h2 (str "Project: " (.getCanonicalPath (io/file ".")))]
      [:ul
       [:li [:a {:href "/release-snapshots"} "Release Snapshots"]]]

      (let [{:keys [servers]} dev-http]
        (when (seq servers)
          (html
            [:h2 "HTTP Servers"]
            [:ul
             (for [{:keys [build-id instance] :as srv} (:servers dev-http)
                   :let [{:keys [http-port https-port]} instance]]
               (let [url (str "http" (when https-port "s") "://localhost:" (or https-port http-port))]
                 [:li [:a {:href url} (str url " - " (pr-str build-id))]]))])))

      [:div#root]
      (assets/js-queue :none 'shadow.cljs.ui.app/init)
      )))

(defn bundle-info-page [{:keys [config] :as req} build-id]
  (let [file (io/file (:cache-root config) "builds" (name build-id) "release" "bundle-info.edn")]
    (if-not (.exists file)
      (common/not-found req "bundle-info.edn not found, please run shadow-cljs release")
      (common/page-boilerplate req
        (html
          [:h1 "shadow-cljs - bundle info"]
          [:div#root]
          (assets/js-queue :none 'shadow.cljs.ui.bundle-info/init
            (edn/read-string (slurp file)))
          )))))

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/bundle-info/{build-id:keyword}" bundle-info-page build-id)
    (:ANY "^/release-snapshots" release-snapshots/root)
    (:ANY "^/api" web-api/root)
    (:ANY "^/ws" ws/process-ws)
    (:ANY "^/worker" ws/process-req)
    common/not-found))
