(ns code-split.b
  (:require [code-split.a :as a]))

(js/console.log ::a/a ::b 1 "foo" (a/foo 1))