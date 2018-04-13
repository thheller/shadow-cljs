(ns demo.rn
  (:require
    ["expo" :as expo]
    ["react" :as react :rename {createElement $}]
    ["react-native" :as rn :refer (Text View)]
    ["create-react-class" :as crc]))

(js/console.log "foo")

(defn render []
  ($ View nil
    ($ Text nil "Hello World from CLJS! 1")
    ($ Text nil "Hello World from CLJS! 2222")
    ($ Text nil "Hello World from CLJS! 3")))

(def Root (crc #js {:render render}))

(expo/registerRootComponent Root)


