(ns shadow.expo
  (:require
    ["expo" :as expo]
    ["create-react-class" :as crc]))

(defonce render-fn-ref (atom nil))
(defonce root-ref (atom nil))

(defn render-root [render-fn]
  (let [first-call? (nil? @render-fn-ref)]
    (reset! render-fn-ref render-fn)

    (if-not first-call?
      (when-let [root @root-ref]
        (.forceUpdate root))
      (let [Root
            (crc
              #js {:componentDidMount
                   (fn []
                     (this-as this
                       (reset! root-ref this)))
                   :render
                   (fn []
                     (@render-fn-ref))})]

        (expo/registerRootComponent Root)))))


