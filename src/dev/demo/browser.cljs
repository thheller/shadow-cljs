(ns demo.browser
  (:require-macros [demo.browser :refer (test-macro)])
  (:require ["react" :as react :refer (createElement)]
            ["react-dom" :as rdom :refer (render)]
            ["shortid" :as sid]
            ["jquery" :as jq]
            ["material-ui" :as mui]
            ["js-nacl" :as nacl-factory]
            [cljsjs.react]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen])
  (:import ["react" Component]))

(assoc nil :foo 1)

(goog-define FOO "foo")

(prn :foo :bar)

(js/console.log "shortid" (sid))

(js/console.log "react cljsjs" (identical? js/React react))

(js/console.log "demo.browser" react rdom Component)

(js/console.log "foo" FOO)

(js/console.log "jq" (-> (jq "body")
                         (.append "foo")))

(defn use-nacl [nacl]
  (let [bytes (.. nacl (random_bytes 16))]
    (js/console.log "nacl" bytes (.. nacl (to_hex bytes)))))

(nacl-factory/instantiate use-nacl)

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


