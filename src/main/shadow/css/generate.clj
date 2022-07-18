(ns shadow.css.generate
  (:require
    [shadow.css.specs :as s]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.edn :as edn])
  (:import
    [java.io StringWriter Writer]))

