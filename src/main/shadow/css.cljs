(ns shadow.css
  (:require [shadow.css.defs])
  (:require-macros [shadow.css]))

;; GOAL: zero runtime size. no css generation in client side code

;; calls directly emitted by css macro
;; this will be replaced with id generator values once classname minification/replacement is done
;; and the call is removed entirely by :advanced
(defn sel
  {:jsdoc ["@idGenerator {mapped}"]}
  [id]
  id)