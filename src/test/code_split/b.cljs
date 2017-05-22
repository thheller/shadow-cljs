(ns code-split.b
  (:require [code-split.a :as a]))

;; (defmethod a/foo :b [_] :b)

;; (def x "code-split.b")

(js/console.log ::a/a ::b js/process (assoc nil ::b 1) 1 "foo")