(ns demo.fulcro-expo
  (:require
    ["expo" :as ex]
    ["react-native" :as rn]
    ["react" :as r]
    [fulcro.client :as fc]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.expo :as expo]
    ))

(defonce root-ref (atom nil))

(defonce app-ref (atom nil))

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

(defsc Root [this props]
  {:initial-state
   (fn [p]
     {::foo "hello world"})

   :query
   [::foo]}

  (r/createElement rn/View #js {:style (.-container styles)}
    (r/createElement rn/Text #js {:style (.-title styles)} "Hello!")
    (r/createElement rn/Text nil (pr-str props))))

(defn start
  {:dev/after-load true}
  []
  (reset! app-ref (fc/mount @app-ref Root :i-got-no-dom-node)))

(defn init []
  (let [app
        (fc/make-fulcro-client
          {:client-did-mount
           (fn [{:keys [reconciler] :as app}])

           :reconciler-options
           {:root-render expo/render-root
            :root-unmount (fn [node])}})]

    (reset! app-ref app)
    (start)))

