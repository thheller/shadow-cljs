(ns demo.browser
  (:require-macros [demo.browser :refer (test-macro)])
  (:require ["react" :as react :refer (createElement)]
            ["react-dom" :as rdom :refer (render)]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import ["react" Component]))

(assoc nil :foo 1)

(prn :foo)

(js/console.log "demo.browser" react rdom Component)

(s/def ::foo string?)

(s/fdef foo
  :args (s/cat :foo ::foo))

(defn foo [x]
  (createElement "h1" nil (str "hello from react: " x)))

(render (foo "foo") (js/document.getElementById "app"))


(defn ^:export start []
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

(defrecord Foo [a b])

(js/console.log (pr-str Foo) (pr-str (Foo. 1 2)))

(js/console.log (test-macro 1 2 3))


