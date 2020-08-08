(ns shadow.react-native
  (:require
    ["react-native" :as rn]
    ["create-react-class" :as crc]))

(defonce root-ref (atom nil))
(defonce root-component-ref (atom nil))

(defn render-root
  "Renders the application root component in a hot-reloadable way,
   should be called after every reload.

   - app-id (string) is used as the AppRegister.registerComponent name
     it cannot be changed after the first call and must match the configured
     app name from react-native.
   - root is expected to be a react element or a function producing one

   Reagent example:

   (ns my.app
     (:require [shadow.react-native :refer (render-root)]
               [reagent.core :as r]
               [\"react-native\" :as rn))

   (defn root []
      [:> rn/View ...])

   (defn ^:dev/after-load start []
     (render-root \"AwesomeApp\" (r/as-element [root])))

   (defn init []
     (start))

   with :init-fn my.app/init in the shadow-cljs.edn build config."
  [app-id root]
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

        (rn/AppRegistry.registerComponent app-id (fn [] Root))))))
