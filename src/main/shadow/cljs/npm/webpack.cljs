(ns shadow.cljs.npm.webpack
  (:require ["webpack" :as webpack :refer (DllPlugin)]
            ["path" :as path]))

(defn webpack-callback [err data]
  (prn [:webpack err data]))

(defn dll-plugin []
  (let [dll-config
        #js {:path (path/resolve "out" "webpack-dll" "js" "a-manifest.json")
             :name "dummy"}]

    (DllPlugin. dll-config)))

(defn main []
  (let [config
        (-> {:context (path/resolve "out" "webpack-dll")
             :entry ["./a"]
             :output
             {:path (path/resolve "out" "webpack-dll" "js")
              :filename "a.js"
              :library "dummy"}
             :plugins [(dll-plugin)]}
            (clj->js))]

    (webpack config webpack-callback)))
