(ns shadow.debug.server
  (:require [shadow.debug :as dbg]
            [shadow.undertow :as undertow]
            [shadow.jvm-log :as log]
            [clojure.java.io :as io]
            [ring.middleware.resource :as ring-resource]
            [ring.middleware.content-type :as ring-content-type]
            [ring.middleware.file :as ring-file]
            [ring.middleware.file-info :as ring-file-info]
            [shadow.cljs.devtools.server.ring-gzip :as ring-gzip]
            [shadow.http.router :as http]
            [shadow.cljs.devtools.server.dev-http :as dev-http]
            [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]
            [ring.middleware.params :as ring-params]
            [shadow.markup.hiccup :as html :refer (defstyled)]
            [clojure.pprint :refer (pprint)]
            [clojure.edn :as edn])
  (:import [java.net URLEncoder]
           [com.google.common.html HtmlEscapers]))

(defn uri-encode [s]
  (URLEncoder/encode s "utf-8"))

(defn html-escape [s]
  (-> (HtmlEscapers/htmlEscaper)
      (.escape s)))

(defn page [^String body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body
   (html5 {}
     [:head
      [:title "shadow.debug"]
      [:style "body { font-family: monospace; font-size: 14px; }"]]
     body

     )})

(defn index-page [{:keys [values-ref] :as req}]
  (-> (html
        [:body
         [:h1 "shadow.debug"]
         [:ul
          (for [key (->> (keys @values-ref)
                         (map pr-str)
                         (sort))]
            [:li [:a {:href (str "/inspect?key=" (uri-encode key))} key]])]])
      (page)))

(defn inspect-page [{:keys [values-ref] :as req}]
  (let [key
        (-> (get-in req [:ring-request :params "key"])
            (edn/read-string))

        query-path
        (get-in req [:ring-request :params "path"] "")

        query-path'
        (-> (str "[" query-path "]")
            (edn/read-string))

        path
        (into [key] query-path')

        value
        (get-in @values-ref path)]

    (-> (html
          [:body

           [:div
            [:a {:href "/"} "index"]]
           [:form {:method "get" :action "/inspect"}
            [:input {:type "hidden" :name "key" :value (pr-str key)}]
            [:input {:type "text" :name "path" :autofocus true :value query-path}]
            [:button {:type "submit"} "lookup"]]
           [:pre
            (-> (with-out-str
                  (binding [*print-length* 10]
                    (pprint value)))
                (html-escape))]])
        (page))))

(defn not-found [req]
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not found."})

(defn root [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/inspect" inspect-page)
    not-found))

(defn get-ring-handler [app-promise]
  (fn [ring-map]
    (let [app (deref app-promise 1000 ::timeout)]
      (if (= app ::timeout)
        {:status 501
         :body "App not ready!"}
        (try
          (-> app
              (assoc :ring-request ring-map)
              (http/prepare)
              (root))
          (catch Exception e
            (log/warn-ex e ::http-handler-ex)
            {:status 500
             :headers {"Content-Type" "text/plain"}
             :body "Request failed."}))))))

(defn start
  [{:keys [http-port http-root]
    :or {http-port 0}
    :as config}
   values-ref]
  (let [root-dir
        (io/file http-root)

        app-promise
        (promise)

        http-handler
        (get-ring-handler app-promise)

        middleware-fn
        #(-> %
             (ring-params/wrap-params)
             (ring-content-type/wrap-content-type)
             (cond->
               root-dir
               (ring-file/wrap-file root-dir {:allow-symlinks? true
                                              :index-files? true}))
             (ring-file-info/wrap-file-info
               ;; source maps
               {"map" "application/json"})

             (ring-gzip/wrap-gzip)
             (dev-http/disable-all-kinds-of-caching))

        http-options
        {:port http-port
         :host "0.0.0.0"}

        {:keys [http-port] :as server}
        (undertow/start http-options http-handler middleware-fn)]

    (log/debug ::server {:http-port http-port})

    (let [app {:http server
               :values-ref values-ref}]
      (deliver app-promise app)
      app)))

(defn stop [{:keys [http] :as srv}]
  (undertow/stop http))

(defn inspect-value [{:keys [values-ref] :as srv} {:keys [id value] :as msg}]
  (swap! values-ref assoc id value))

(defn inspect-log [{:keys [values-ref] :as srv} {:keys [id value keep] :as msg}]
  (swap! values-ref update id (fn [current]
                                (let [c (count current)]
                                  (-> (or current [])
                                      (conj value)
                                      (cond->
                                        (>= c keep)
                                        (->> (rest) (into []))
                                        ))))))

(defn process-tap [srv {:keys [tag] :as msg}]
  (case tag
    :inspect/value
    (inspect-value srv msg)

    :inspect/log
    (inspect-log srv msg)

    :else
    (log/debug ::unhandled-tap msg)))

(defonce instance-ref (atom nil))
(defonce values-ref (atom {}))

(defn start! [config]
  (when-not @instance-ref
    (let [inst (start config values-ref)]
      (reset! instance-ref inst)
      (dbg/add-tap ::server #(process-tap inst %))
      )))

(defn stop! []
  (when-let [inst @instance-ref]
    (dbg/remove-tap ::server)
    (stop inst)
    (reset! instance-ref nil)
    ))

(defn empty! []
  (reset! values-ref {}))
