(ns demo.browser
  (:require ["react" :as react :refer (createElement)]
            ["react-dom" :as rdom :refer (render)])
  (:import ["react" Component]))

(defn foo []
  (createElement "h1" nil "hello from react"))

(render (foo) (js/document.getElementById "app"))

(js/console.log "demo.browser" react rdom)

(prn :foo)

(defn ^:export start []
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

(defrecord Foo [a b])

(js/console.log (pr-str Foo) (pr-str (Foo. 1 2)))
