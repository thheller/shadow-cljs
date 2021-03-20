(ns shadow.cljs.devtools.server.web
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [hiccup.core :refer (html)]
    [hiccup.page :refer (html5)]
    [ring.middleware.file :as ring-file]
    [ring.middleware.file-info :as ring-file-info]
    [shadow.http.router :as http]
    [shadow.server.assets :as assets]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.api :as web-api]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.api :as api]
    [shadow.jvm-log :as log]
    [ring.middleware.params :as ring-params]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.graph.env :as genv]
    [shadow.cljs.devtools.server.dev-http :as dev-http]))

(defn create-index-handler [{:keys [db] :as env}]
  (fn index-handler [request]
    {:status 200
     :body "hello world"}))

(defn index-page [req]
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"}
   :body
   (html5
     {:lang "en"}
     [:head
      ;; starting the worker ASAP
      ;; if the script starts it we have to wait for the script to download and execute
      ;; [:link {:rel "preload" :as "worker" ...}] isn't supported yet
      #_[:script
         (str "var SHADOW_WORKER = new Worker(\"/js/worker.js?server-token="
              (get-in req [:http :server-token])
              "\");")]
      [:link {:href "/img/shadow-cljs.png" :rel "icon" :type "image/png"}]
      [:title (-> (io/file ".")
                  (.getCanonicalFile)
                  (.getName))]
      [:link {:rel "stylesheet" :href "/css/main.css"}]
      [:link {:rel "stylesheet" :href "/css/tailwind.min.css"}]
      [:meta {:name "shadow-remote-token" :content (get-in req [:http :server-token])}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]]
     [:body
      [:div#root.fixed.inset-0]
      [:script {:src "/js/shared.js" :defer true}]
      [:script {:src "/js/main.js" :defer true}]])})

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

(defn browser-repl-js [{:keys [config supervisor] :as req} build-id]
  (let [{:keys [state-ref] :as worker}
        (or (super/get-worker supervisor build-id)
            (-> (api/start-browser-repl* req {:build-id build-id})
                (worker/compile)))]

    (worker/sync! worker)

    ;; FIXME: technically when loading this page the worker should be reset/recompiled
    ;; since all previous JS state is lost

    (-> (cond
          (nil? worker)
          {:status 404
           :header {"content-type" "text/plain"}
           :body "browser-repl not running."}

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

              [:script {:src (str "/cache/" (name build-id) "/js/repl.js") :defer true}]])})
        (no-cache!))))

(defn browser-test-page [{:keys [supervisor] :as req}]
  (let [worker (super/get-worker supervisor :browser-test)]

    (when-not worker
      (let [config
            {:build-id :browser-test
             :target :browser-test
             :devtools {:loader-mode :eval}
             :asset-path "/cache/browser-test/out/js"
             :test-dir ".shadow-cljs/builds/browser-test/out"}]

        (log/debug ::browser-test-start config)

        ;; FIXME: access to CLI opts from server start?
        (-> (super/start-worker supervisor config {})
            (worker/start-autobuild)
            (worker/sync!))))

    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body
     (html5
       {:lang "en"}
       [:head
        [:title "browser-test"]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
       [:body
        [:pre#log]
        [:script {:src "/cache/browser-test/out/js/test.js"}]
        [:script "shadow.test.browser.init();"]])}))

(defn workspaces-page [{:keys [supervisor] :as req}]
  (if-not (io/resource "nubank/workspaces/core.cljs")
    {:status 404
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body "nubank.workspaces.core namespace not found on classpath!"}

    (let [worker
          (or (super/get-worker supervisor :workspaces)
              (let [config
                    {:build-id :workspaces
                     :target :browser-test
                     :ns-regexp "-cards$"
                     :runner-ns 'shadow.test.workspaces
                     :devtools {:loader-mode :eval}
                     :asset-path "/cache/workspaces/out/js"
                     :test-dir ".shadow-cljs/builds/workspaces/out"}]

                (log/debug ::workspaces-start config)

                (-> (super/start-worker supervisor config {})
                    (worker/start-autobuild))))]

      (worker/sync! worker)

      {:status 200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body
       (html5
         {:lang "en"}
         [:head
          [:title "workspaces"]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          ;; FIXME: local copy?
          [:link {:rel "stylesheet" :href "https://fonts.googleapis.com/css?family=Open+Sans"}]
          [:link {:rel "stylesheet" :href "//cdnjs.cloudflare.com/ajax/libs/highlight.js/9.12.0/styles/github.min.css"}]]
         [:body
          [:div#app]
          [:script {:src "/cache/workspaces/out/js/test.js"}]
          [:script "shadow.test.workspaces.init();"]
          ])})))

(defonce active-cards-clients (atom 0))

(defn grove-cards-page [{:keys [supervisor] :as req}]
  (if-not (io/resource "shadow/experiments/grove/cards/env.cljs")
    {:status 404
     :headers {"content-type" "text/plain; charset=utf-8"}
     :body "shadow.experiments.grove.cards.env namespace not found on classpath!"}

    (let [worker
          (or (super/get-worker supervisor :grove-cards)
              (let [config
                    {:build-id :grove-cards
                     :target :browser-test
                     :ns-regexp "-cards$"
                     :runner-ns 'shadow.experiments.grove.cards.runner
                     :asset-path "/cache/grove-cards/out/js"
                     :test-dir ".shadow-cljs/builds/grove-cards/out"}]

                (log/debug ::grove-cards-start config)

                (-> (super/start-worker supervisor config {})
                    (worker/start-autobuild))))]

      (worker/sync! worker)

      (swap! active-cards-clients inc)

      {:status 200
       :headers {"content-type" "text/html; charset=utf-8"}
       :body
       (html5
         {:lang "en"}
         [:head
          [:title "grove cards"]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
          [:link {:rel "stylesheet" :href "/css/tailwind.min.css"}]]
         [:body
          [:div#app]
          [:script {:src "/cache/grove-cards/out/js/test.js"}]
          [:script "shadow.experiments.grove.cards.runner.init();"]
          [:script
           (str "window.addEventListener(\"beforeunload\", function() {"
                "navigator.sendBeacon(\"/grove/cards-unload\", \"\");"
                "});")]
          ])})))

(defn grove-cards-unload [{:keys [supervisor] :as req}]
  (future
    ;; FIXME: delay this a bit since it might just be a page reload
    (Thread/sleep 1000)
    (swap! active-cards-clients dec)
    (when (zero? @active-cards-clients)
      (super/stop-worker supervisor :grove-cards)))

  {:status 201
   :headers {}
   :body ""})

(defn maybe-index-page [req]
  (let [accept (get-in req [:ring-request :headers "accept"])]
    (if (and accept (not (str/includes? accept "text/html")))
      (common/not-found req)
      (index-page req))))

(defn pages [req]
  (-> req
      (http/route
        (:GET "" index-page)
        (:GET "^/cache" serve-cache-file)
        (:GET "/repl-js/{build-id:keyword}" browser-repl-js build-id)
        (:GET "/browser-test" browser-test-page)
        (:GET "/workspaces" workspaces-page)
        (:GET "/grove/cards" grove-cards-page)
        (:ANY "/grove/cards-unload" grove-cards-unload)
        maybe-index-page #_common/not-found)))

(defn root [req]
  (-> req
      (update :ring-request ring-params/params-request {})
      (http/route
        ;; temp fix for middleware problem
        (:ANY "/api/remote-relay" web-api/api-remote-relay)
        (:ANY "^/api" web-api/root)
        (:GET "^/cache" serve-cache-file)
        pages)))