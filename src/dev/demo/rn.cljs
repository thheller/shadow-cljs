(ns demo.rn
  (:require
    ["react" :as react :rename {createElement $}]
    ["react-native" :as rn :refer (Text View)]
    [shadow.expo :as expo]
    ))

(defn render-root []
  ($ View nil
    ($ Text nil "Hello World from CLJS! 1")
    ($ Text nil "Hello World from CLJS! 2")
    ($ Text nil "Hello World from CLJS! 3")))

(defn ^:dev/after-load start []
  (expo/render-root (render-root)))

(defn init []
  (start))