(ns code-split.b
  (:require [code-split.a :as a]))

(js/console.log :foo :X ::a/a ::b 1 "foo" (a/foo 1))