(ns cljs.test-stubs
  (:require-macros [cljs.test-stubs]))

(defn empty-env
  ([])
  ([reporter]))

(defn test-var [v])

(defn test-vars-block [vars])

(defn test-vars [vars])

(defn successful? [summary])

