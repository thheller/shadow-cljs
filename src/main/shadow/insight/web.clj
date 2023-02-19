(ns shadow.insight.web
  (:require
    [hiccup.page :refer (html5)]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.http.router :as http]
    [shadow.jvm-log :as log]))

(defn iframe
  [{:keys [runtime supervisor] :as req} build-id]
  (let [worker
        (or (super/get-worker supervisor build-id)

            ;; FIXME: get base config from shadow-cljs.edn, might need custom options
            (let [config
                  {:build-id build-id
                   :target :esm
                   :modules {:main {:init-fn 'shadow.insight.ui/init}}
                   :output-dir (str ".shadow-cljs/builds/" (name build-id) "/js")}]

              (log/debug ::insight-ui-start config)

              ;; FIXME: access to CLI opts from server start?
              (-> (super/start-worker supervisor config {})
                  (worker/start-autobuild))))]

    (worker/sync! worker)

    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body
     (html5
       {:lang "en"}
       [:head
        [:script {:type "module" :src (str "/cache/" (name build-id) "/js/main.js")}]
        [:title "shadow.insight"]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]]
       [:body])}))


(defn handler [req]
  (-> req
      (http/route
        (:GET "/iframe/{build-id:keyword}" iframe build-id)
        common/not-found)))
