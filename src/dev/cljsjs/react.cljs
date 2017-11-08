(ns cljsjs.react
  (:require ["react" :as react]
            ["create-react-class" :as crc]))

(js/goog.object.set react "createClass" crc)

(js/goog.exportSymbol "React" react)