(ns demo.rn
  (:require
    ["react-native" :as rn]
    ["react" :as react]
    ["create-react-class" :as crc]))

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

(defonce root-component-ref (atom nil))
(defonce root-ref (atom nil))

(defn render-root [root]
  (let [first-call? (nil? @root-ref)]
    (reset! root-ref root)

    (if-not first-call?
      (when-let [root @root-component-ref]
        (.forceUpdate ^js root))
      (let [Root
            (crc
              #js {:componentDidMount
                   (fn []
                     (this-as this
                       (reset! root-component-ref this)))
                   :componentWillUnmount
                   (fn []
                     (js/console.log "rootWillUnmount")
                     (reset! root-component-ref nil))
                   :render
                   (fn []
                     (let [body @root-ref]
                       (if (fn? body)
                         (body)
                         body)))})]

        (rn/AppRegistry.registerComponent "TestCRNA" (fn [] Root))))))

(defn start
  {:dev/after-load true}
  []
  (render-root (root)))

(defn init []
  (start))