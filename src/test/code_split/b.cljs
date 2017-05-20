(ns code-split.b
  (:require [code-split.a :as a]))

(defmethod a/foo :b [_] :b)

(def x "code-split.b")