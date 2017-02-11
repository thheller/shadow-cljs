(ns build
  (:require [shadow.devtools.sass :as sass]
            [shadow.cljs.build :as cljs]
            ))

(def css-packages
  [{:name "devtools"
    :modules ["src/css/ui.scss"]
    :public-dir "public/assets/devtools/css"
    :public-path "/assets/devtools/css"}
   {:name "client"
    :modules ["src/css/client.scss"]
    :public-dir "public/assets/client/css"
    :public-path "/assets/client/css"}]

  )

(defn css [& args]
  (sass/build-packages css-packages))
