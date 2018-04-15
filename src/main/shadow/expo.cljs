(ns shadow.expo
  (:require
    ["expo" :as expo]
    ["create-react-class" :as crc]))

(defonce root-ref (atom nil))
(defonce root-component-ref (atom nil))

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
                     (reset! root-component-ref nil))
                   :render
                   (fn []
                     (let [body @root-ref]
                       (if (fn? body)
                         (body)
                         body)))})]

        (expo/registerRootComponent Root)))))


