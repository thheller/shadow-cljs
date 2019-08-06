(ns demo.rn-foo
  (:require
    ["react-native" :as rn]
    ["react" :as r]))

(defn component []
  (r/createElement rn/Text nil "Hello Foo!"))
