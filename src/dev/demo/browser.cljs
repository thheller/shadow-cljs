(ns demo.browser
  (:require-macros [demo.browser :refer (test-macro)])
  (:require
    ["react" :as react :refer (Component createElement)]
    ["react-dom" :as rdom :refer (render)]
    ["shortid" :as sid]
    ["jquery" :as jq]
    ["babel-test" :as babel-test :default Shape]
    ["@material/checkbox" :refer (MDCCheckbox MDCCheckboxFoundation)]
    ["@material/menu/simple/foundation" :default menu]
    ["@material/menu/util" :as util]
    ["d3" :as d3]
    [clojure.pprint :refer (pprint)]
    [cljsjs.react]
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [shadow.api :refer (ns-ready)]
    [cljs.core.async :as async :refer (go)]
    ["./es6.js" :as es6]
    ["./foo" :as foo]
    ["circular-test" :as circ]
    [cljs.test :refer (deftest)]
    [cljs.loader]
    ))

(deftest yo
  (= 1 2))

(js/console.log "or" (or nil js/document.body))

(go (<! (async/timeout 500))
    (js/console.log "go!"))

(es6/someAsyncFn (js/fetch "/index.html"))

(pprint [1 2 3])

(assoc nil :foo 1)

(js/console.log "es6" (es6/foo "es6"))

(goog-define FOO "foo")

#_(def mdc-checkbox (MDCCheckbox. (js/document.getElementById "material")))

(js/console.log :foo)
(prn :foo1 :bar)
(prn :foo2 :bar)
(prn :foo3 :bar)
(prn :foo4 :bar)
(prn :foo5 :bar)
(prn :foo6 :bar)
(prn :foo7 :bar)
(prn :foo8 :bar)
(prn :foo9 :bar)
(prn :foo0 :bar)


(js/console.log "menu" menu)
(js/console.log "util" util)

(js/console.log "babel-test" babel-test (Shape. 1 1))

(js/console.log "shortid" (sid))

(js/console.log "react cljsjs" (identical? js/React react))

(js/console.log "demo.browser" react rdom Component)

(js/console.log "foo" FOO foo)

(js/console.log "jq" (-> (jq "body")
                         (.append "foo")))

(js/console.log "circular - not yet" (circ/test))
(js/console.log "circular - actual" (circ/foo))

(s/def ::foo string?)

(s/fdef foo
  :args (s/cat :foo ::foo))

(defn foo [x]
  (createElement "h1" nil (str "hello from react: " x)))

(render (foo "foo") (js/document.getElementById "app"))

(defn ^:export start []
  (js/console.log "browser-start"))

(defn stop [done]
  (js/console.log "browser-stop async")
  (js/setTimeout
    (fn []
      (js/console.log "browser-stop async complete")
      (done))
    500))

(defrecord Foo [a b])

(js/console.log (pr-str Foo) (pr-str (Foo. 1 2)) (Foo. 1 2))

(js/console.log (test-macro 1 2 3))

(js/console.log {:something [:nested #{1 2 3}]})

(defn tokenize []
  (js/console.log "REMOVE-CHECK"))

(ns-ready)

(goog-define DEBUG false)

(when DEBUG
  (js/console.log "foo2"))
