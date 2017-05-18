(ns demo.browser
  (:require ["react" :as react]
            ["react-dom" :as rdom]))

(defn foo []
  (react/createElement "h1" nil "hello from react"))

(rdom/render (foo) (js/document.getElementById "app"))

(js/console.log "demo.browser")

(prn :foo)

(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

