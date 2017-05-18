(ns demo.browser
  (:require ["react" :as react :refer (createElement)]
            ["react-dom" :as rdom :refer (render)])
  (:import ["react" Component]))

(defn foo []
  (createElement "h1" nil "hello from react"))

(render (foo) (js/document.getElementById "app"))

(js/console.log "demo.browser" Component)

(prn :foo)


(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))
