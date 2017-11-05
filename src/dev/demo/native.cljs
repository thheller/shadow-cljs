(ns demo.native
  (:require ["react" :as r :refer (thing)]
            [demo.protocol :as dp]
            [clojure.string :as string]
            [clojure.set :as set]))

;; lots of native interop, not actually valid code, just for testing externs generator
(set! *warn-on-infer* true)

(thing)

(.nested (.test (r/xyz)))
(.bar r)

(defn x [^js y]
  (.. y (jsFun) -jsProp (jsFunTwo)))

(defn wrap-baz [x]
  (.baz x))

(js/foo.bar.xyz)
(js/goog.object.set nil nil)
(js/cljs.core.assoc nil :foo :bar)


(defn thing [{:keys [foo] :as this}]
  (.componentDidUpdate ^js this))

(defn thing2 [simple]
  (.componentDidUpdate simple))

(deftype ShouldNotWarnAboutInfer [foo bar]
  Object
  (yo [x])

  ILookup
  (-lookup [this key]
    ::fake))

(extend-protocol dp/SomeProtocol
  ShouldNotWarnAboutInfer
  (some-protocol-fn [this x]
    x))

(defrecord SomeRecord [x y])

(implements? SomeProtocol (SomeRecord. 1 2))

(list)
;; foo ;; warning, to prevent cache
