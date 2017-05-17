(ns demo.browser
  #_(:js/require
      ["react" :as react :refer (Component)]
      ["react-dom" :as rdom :refer (render)]))



(defn foo []
  #_ (react/createElement "h1" nil "hello from react"))

#_ (rdom/render (foo) (js/document.getElementById "app"))

(js/console.log "demo.browser" Component)

(prn :foo)

(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

