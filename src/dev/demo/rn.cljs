(ns demo.rn
  (:require
    ["react" :as react :rename {createElement $}]
    ["react-native" :as rn :refer (Button Text View)]
    [shadow.expo :as expo]
    ))

(defn fail []
  (throw (ex-info "failed" {})))

(defn render-root []
  ($ View nil
    ($ Text nil "Hello World from CLJS! 1")
    ($ Text nil "Hello World from CLJS! 2")
    ($ Text nil "Hello World from CLJS! 3")
    ($ Button #js {:title "Hello World" :onPress fail})))

(defn ^:dev/after-load start []
  (expo/render-root (render-root)))

(defn init []
  (start))