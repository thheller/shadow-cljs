(ns demo.browser
  {:shadow.sass/include
   ["./foo.scss"]}
  (:require-macros [demo.browser :refer (test-macro)])
  (:require
    #_["babel-test" :as babel-test :default Shape]
    [cljs.test :as ct]
    [clojure.pprint :refer (pprint)]
    [clojure.spec.alpha :as s]
    [clojure.spec.gen.alpha :as gen]
    [cljs.core.async :as async :refer (go alt!)]
    ["./es6.js" :as es6]
    ["./foo.js" :as foo]
    #_["circular-test" :as circ]
    #_["/demo/myComponent" :refer (myComponent)]
    [demo.macro :as m]
    [shadow.resource :as rc]
    [shadow.loader :as sl]
    [cljs.loader :as cl]
    [shadow.lazy :as lazy]
    ["./cjs.js" :as cjs]
    [demo.js-class :refer (Foo)]
    ["dyn-import" :as dyn]
    [shadow.cljs.modern :refer (js-await)]
    [fake.ns :as-alias alias]
    [demo.dummy-cljc]
    [demo.didnt-follow-the-rules :as rule-breaker])
  (:import [goog.module ModuleLoader]))

(js/console.log (goog.object/get #js {:foo "foo"} "foo"))

(js/console.log "foo" `alias/bar ::alias/bar)

(js-await [mod (dyn/bar)]
  (js/console.log "dyn-import" mod (.bar mod)))

(js/console.log "ml" ModuleLoader)
(js/console.log "es6" es6 es6/default)

;; (throw (js/Error. "foo"))
;; (throw (ex-info "foo" {:foo 1}))

(assert (= 1 rule-breaker/foo) "rule-breaker not handled correctly")

(defn custom-notify [{:keys [type] :as msg}]
  (js/console.warn "CUSTOM-NOTIFY" type msg))

(when-not (identical? sl/load cl/load)
  (js/console.warn "cljs.loader NOT aliased to shadow.loader"))

(def x (rc/inline "./test.md"))

(js/console.log "test.md" x)

(ct/deftest this-is-no-test
  (ct/is (= "actually kind of" 1)))

(defn yo
  {:test #(ct/is (= "also kind of" 1))}
  [bar]
  (.fromSimpleExterns bar))

;; (throw (ex-info "fail to load" {}))

(s/def ::color
  (s/or :keyword
    keyword?
    :literal
    (s/and string?
           #(re-matches #"#[a-fA-F0-9]+" %)
           #(or (= (count %) 7) (= (count %) 4)))))

(defn thing [^js foo]
  (.inferMePlz foo))

#_(go (<! (async/timeout 500))
      (js/console.log "go!"))

#_(when js/goog.global.fetch
    (es6/someAsyncFn (js/fetch "/index.html")))

#_(js/console.log "JSX" (myComponent))

#_(pprint [1 2 3])

#_(assoc nil :foo 1)

#_(js/console.log "es6" (es6/foo "es6"))

;; (defn x 1)

#_(js/console.log :foo)
#_(prn :foo1 :bar)

#_(js/console.log "babel-test" babel-test (Shape. 1 1))

#_(js/console.log "foo" foo)

#_(js/console.log "circular - not yet" (circ/test))
#_(js/console.log "circular - actual" (circ/foo))

(s/def ::foo string?)

(s/fdef foo
  :args (s/cat :foo ::foo))

(defonce worker-ref (atom nil))

(defn ^:dev/after-load start []
  (js/console.log "browser-start")
  (set! (.-innerHTML (js/document.querySelector "h1")) "loaded!")
  )

(defn start-from-config []
  (js/console.log "browser-start-from-config"))

(defn init []
  (js/console.warn "browser-init")

  (let [worker (js/Worker. "/js/worker.js")]
    (reset! worker-ref worker)

    (.addEventListener worker "message"
      (fn [e]
        (js/console.log "mesage from worker" e))))

  (start))

(defn ^:dev/before-load stop-sync []
  #_(.terminate @worker-ref)
  (js/console.log "browser-stop-sync"))

(defn ^:dev/before-load-async stop [done]
  (js/console.log "browser-stop async")
  (js/setTimeout
    (fn []
      (js/console.log "browser-stop async complete")
      (done))
    10))

(defn stop-from-config [done]
  (js/console.log "browser-stop-from-config async")
  (js/setTimeout
    (fn []
      (js/console.log "browser-stop-from-config async complete")
      (done))
    10))

(defrecord Rec [a b])

(js/console.log (pr-str Rec) (pr-str (Rec. 1 2)) (Rec. 1 2))

(js/console.log "test-macro" (test-macro 1 2 3))

(comment
  (js/console.log (test-macro 1 2 3))

  (js/console.log {:something [:nested #{1 2 3}]}))

(goog-define DEBUG false)

(when DEBUG
  (js/console.log "foo2"))

;; for testing load-file result
(def the-last-form "is-a-var")