(ns demo.rn
  (:require
    [shadow.react-native :refer (render-root)]
    ["react-native" :as rn]
    ["react" :as react]))

(def styles
  ^js (-> {:container
           {:flex 1
            :backgroundColor "#fff"
            :alignItems "center"
            :justifyContent "center"}
           :title
           {:fontWeight "bold"
            :fontSize 24
            :color "blue"}}
          (clj->js)
          (rn/StyleSheet.create)))

(defn bad-press [e]
  (js/console.log "pressed the bad button")
  (throw (ex-info "button pressed" {})))

(js/console.log (js/require "../package.json"))

(defn root []
  (react/createElement rn/View #js {:style (.-container styles)}
    (react/createElement rn/Text #js {:style (.-title styles)} "Hello!")
    (let [comp (js/require "./foo.js")]
      (comp))
    (react/createElement rn/Button #js {:onPress (fn [e] (bad-press e))
                                        :title "error"})))

(defn start
  {:dev/after-load true}
  []
  (render-root "TestCRNA" (root)))

(defn init []
  (start))