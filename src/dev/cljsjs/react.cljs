(ns cljsjs.react
  (:require [goog.object :as gobj]
            ["react" :as react]))

(gobj/set js/goog.global "React" react)