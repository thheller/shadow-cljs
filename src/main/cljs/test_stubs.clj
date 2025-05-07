(ns cljs.test-stubs
  (:require [cljs.test]))

(defmacro is [& body]
  true)

(defmacro are [& body]
  true)

(defmacro deftest [& body]
  nil)

(defmacro testing [& body]
  nil)

(defmacro async [& body]
  nil)

(defmacro run-tests [& body]
  nil)

(defmacro run-test [& body]
  nil)

(defmacro run-all-tests [& body]
  nil)

(defmacro test-all-vars [& body]
  nil)

(defmacro use-fixtures [& body]
  nil)

(defmacro test-ns [& body]
  nil)