(ns shadow.build.targets.browser-test
  (:refer-clojure :exclude (compile flush resolve))
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.util :as util]
            [shadow.build :as build]
            [shadow.build.classpath :as cp]
            [shadow.build.resolve :as resolve]
            [shadow.build.compiler :as impl]
            [shadow.build.data :as data]
            [shadow.build.cache :as cache]
            [shadow.build.modules :as modules]
            [clojure.data.json :as json]
            [shadow.build.api :as build-api]
            [cljs.compiler :as comp]
            [clojure.string :as str]
            [shadow.build.npm :as npm]
            [shadow.build.output :as output]))

(defn modify-config [{::build/keys [config] :as state}]
  (prn [:modify-config config])
  state)

(defn add-test-namespaces [state]
  (prn [:add-test-namespaces])
  state)

(defn process
  [{::build/keys [stage] :as state}]
  (-> state
      (cond->
        (= :configure stage)
        (modify-config)

        (= :compile-prepare stage)
        (add-test-namespaces))

      (browser/process)))