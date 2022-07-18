(ns shadow.css
  (:require [shadow.css.defs])
  (:require-macros [shadow.css]))

;; GOAL: zero runtime size. no css generation in client side code