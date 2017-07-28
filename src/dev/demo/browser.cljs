(ns demo.browser
  (:require-macros [demo.browser :refer (test-macro)])
  (:require ["react" :as react :refer (createElement)]
            ["react-dom" :as rdom :refer (render)]
            [cljs.spec.alpha :as s]
            [cljs.spec.gen.alpha :as gen])
  (:import ["react" Component]))

(s/def ::foo string?)

(s/fdef foo
  :args (s/cat :foo ::foo))

(defn foo [x]
  (createElement "h1" nil "hello from react"))

(render (foo "foo") (js/document.getElementById "app"))

(js/console.log "demo.browser" react rdom)

(prn :foo)

(defn ^:export start []
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

(defrecord Foo [a b])

(js/console.log (pr-str Foo) (pr-str (Foo. 1 2)))

(js/console.log (test-macro 1 2 3))
