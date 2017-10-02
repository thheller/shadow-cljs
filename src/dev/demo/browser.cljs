(ns demo.browser
  (:require-macros [demo.browser :refer (test-macro)])
  (:require ["react" :as react :refer (Component createElement)]
            ["react-dom" :as rdom :refer (render)]
            ["shortid" :as sid]
            ["jquery" :as jq]
            ["circular-test" :as circ]
            ["highlight.js" :as hljs]
            [cljsjs.react]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  #_ (:import ["react" Component]))

(assoc nil :foo 1)

(goog-define FOO "foo")

(prn :foo :bar)

(js/console.log "shortid" (sid))

(js/console.log "react cljsjs" (identical? js/React react))

(js/console.log "demo.browser" react rdom Component)

(js/console.log "foo" FOO)

(js/console.log "jq" (-> (jq "body")
                         (.append "foo")))

(js/console.log "circular - not yet" (circ/test))
(js/console.log "circular - actual" (circ/foo))

(hljs/initHighlightingOnLoad)

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


