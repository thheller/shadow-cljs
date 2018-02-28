(ns shadow.cljs.devtools.server.web
  (:require
    [clojure.string :as str]
    [hiccup.core :refer (html)]
    [hiccup.page :refer (html5)]
    [shadow.http.router :as http]
    [shadow.server.assets :as assets]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.api :as web-api]
    [shadow.cljs.devtools.server.web.release-snapshots :as release-snapshots]
    [shadow.cljs.devtools.server.worker.ws :as ws]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [ring.middleware.file :as ring-file]
    [ring.middleware.file-info :as ring-file-info]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.api :as api]))

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

(defn no-cache! [res]
  (update-in res [:headers] assoc
    "cache-control" "max-age=0, no-cache, no-store, must-revalidate"
    "pragma" "no-cache"
    "expires" "0"))

(defn serve-cache-file
  [{:keys [config ring-request] :as req}]
  (let [root (io/file (:cache-root config "target/shadow-cljs") "builds")
        ring-req
        (update ring-request :uri str/replace #"^/cache" "/")]
    (or (some->
          (ring-file/file-request ring-req root {})
          (ring-file-info/file-info-response ring-req {"transit" "application/json"})
          (no-cache!))
        (common/not-found req))))

(def logo-svg
  (let [s-path
        "M247.183941,141.416413 C247.183941,74.7839971 148.383423,78.9723529 148.383423,141.416413 C148.383423,203.860473 265.090698,171.864644 265.090698,248.900057 C265.090698,325.93547 135,325.851749 135,251.708304"]

    (html
      [:svg
       {:id "shadow-cljs-logo"
        :version "1.1"
        :viewBox "0 0 400 400"
        :height "40px"
        :width "40px"}
       [:title "shadow-cljs"]
       [:defs
        [:mask#shadow-cljs-logo-mask {:fill "#fff"}
         [:circle {:r "200" :cy "200" :cx "200"}]]]
       [:g
        {:fill-rule "evenodd"
         :fill "none"
         :stroke-width "0"
         :stroke "none"
         :mask "url(#shadow-cljs-logo-mask)"}

        [:g.circles
         [:circle.blue {:r "200" :cy "200" :cx "200" :fill "#4F80DF"}]
         [:circle.light-blue {:r "71.5" :cy "200" :cx "370" :fill "#89B4FF"}]
         [:circle.dark-green {:r "180" :cy "360" :cx "60" :fill "#40B400"}]
         [:circle.light-green {:r "129" :cy "320" :cx "280" :fill "#76E013"}]]

        ;; S shadow
        [:g {:transform "translate(10,10)"}
         [:path
          {:stroke-linecap "square"
           :stroke-width "16"
           :stroke "#aaa"
           :d s-path}]]
        ;; S
        [:path
         {:stroke-linecap "square"
          :stroke-width "16"
          :stroke "#FFFFFF"
          :d s-path}]]])))

(defn browser-repl-js [{:keys [config supervisor] :as req}]
  (let [{:keys [state-ref] :as worker}
        (or (super/get-worker supervisor :browser-repl)
            (-> (api/start-browser-repl* req)
                (worker/compile)))]

    (worker/sync! worker)

    ;; FIXME: technically when loading this page the worker should be reset/recompiled
    ;; since all previous JS state is lost

    (-> (cond
        (nil? worker)
        {:status 404
         :header {"content-type" "text/plain"}
         :body "browser-repl not running."}

        ;; test twice in case the websocket disconnect happened concurrently (ie. reload)
        (and (not (empty? (get-in @state-ref [:eval-clients])))
             (do (Thread/sleep 500)
                 (not (empty? (get-in @state-ref [:eval-clients])))))
        {:status 503
         :header {"content-type" "text/plain"}
         :body "browser-repl already open elsewhere."}

        :else
        {:status 200
         :headers {"content-type" "text/html; charset=utf-8"}
         :body
         (html5
           {:lang "en"}
           [:head [:title "shadow-cljs browser-repl"]]
           [:body {:style "font-family: monospace; font-size: 14px;"}
            [:div#app]
            [:div#root]

            [:div
             [:div {:style "float: left; padding-right: 10px;"} logo-svg]
             [:h1 {:style "line-height: 40px;"} "shadow-cljs"]
             [:p "Code entered in a browser-repl prompt will be evaluated here."]]

            [:pre#log]

            [:script {:src "/cache/browser-repl/js/repl.js" :defer true}]])})
        (no-cache!))))

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/bundle-info/{build-id:keyword}" bundle-info-page build-id)
    (:ANY "^/release-snapshots" release-snapshots/root)
    (:ANY "^/api" web-api/root)
    (:ANY "^/ws" ws/process-ws)
    (:ANY "^/worker" ws/process-req)
    (:GET "^/cache" serve-cache-file)
    (:GET "/browser-repl-js" browser-repl-js)
    common/not-found))
