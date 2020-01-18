(ns shadow.cljs.devtools.server.web.common
  (:require [shadow.server.assets :as assets]
            [hiccup.page :refer (html5)]
            [hiccup.core :refer (html)]
            [clojure.java.io :as io]
            [shadow.core-ext :as core-ext]
            [cljs.compiler :as cljs-comp]
            [clojure.data.json :as json]))

(defn not-found
  ([req]
   (not-found req "Not found."))
  ([req msg]
   {:status 404
    :headers {"content-type" "text/plain"}
    :body msg}))

(defn unacceptable [_]
  {:status 406 ;; not-acceptable
   :headers {"Content-Type" "text/plain"}
   :body "websocket required"})

(defn edn [req data]
  {:status 200
   :header {"content-type" "application/edn"}
   :body (core-ext/safe-pr-str data)})

(defn page-boilerplate
  [req {:keys [modules body-class headers init-call]} ^String content]
  {:status 200
   :headers (merge {"content-type" "text/html; charset=utf-8"} headers)
   :body
   (html5
     {:lang "en"}
     [:head
      ;; starting the worker ASAP
      ;; if the script starts it we have to wait for the script to download and execute
      ;; [:link {:rel "preload" :as "worker" ...}] isn't supported yet
      [:script "var SHADOW_WORKER = new Worker(\"/js/worker.js\");"]
      [:link {:href "/img/shadow-cljs.png" :rel "icon" :type "image/png"}]
      [:title (-> (io/file ".")
                  (.getCanonicalFile)
                  (.getName))]
      [:link {:rel "stylesheet" :href "/css/main.css"}]
      [:link {:rel "stylesheet" :href "/css/tailwind.min.css"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"}]]
     [:body {:class body-class}
      content
      (for [x modules]
        [:script {:src (str "/js/" (name x) ".js")}])
      (when-let [[fn-sym fn-arg] init-call]
        [:script (str (cljs-comp/munge fn-sym) "(" (pr-str (pr-str fn-arg)) ");")])
      ])})

(defn nav []
  (html
    [:div
     [:a {:href "/explorer"} "home"]]))

(defn transit [{:keys [transit-str] :as req} obj]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str obj)})