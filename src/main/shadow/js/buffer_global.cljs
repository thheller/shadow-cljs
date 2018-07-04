(ns shadow.js.buffer-global
  (:require
    [goog.object :as gobj]
    [shadow.js :as sjs]
    ["buffer" :as b]))

(gobj/set sjs/shims "Buffer" b/Buffer)