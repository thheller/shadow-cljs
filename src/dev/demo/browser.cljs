(ns demo.browser
  (:require-macros [demo.browser :refer (test-macro)])
  (:require ["react" :as react :refer (createElement)]
            ["react-dom" :as rdom :refer (render)])
  (:import ["react" Component]))

(def ^:const a-const {:hello ["something" {:really #{:nested 1}}]})

(js/console.log "identical?" (identical? a-const a-const))

(defn foo []
  (createElement "h1" nil "hello from react"))

(render (foo) (js/document.getElementById "app"))

(js/console.log "demo.browser" react rdom)

(prn :foo)

(defn ^:export start []
  (js/console.log "browser-start" a-const))

(defn stop []
  (js/console.log "browser-stop"))

(defrecord Foo [a b])

(js/console.log (pr-str Foo) (pr-str (Foo. 1 2)))

(js/console.log (test-macro 1 2 3))
