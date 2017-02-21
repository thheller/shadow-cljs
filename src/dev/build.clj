(ns build
  (:require [shadow.devtools.sass :as sass]
            [shadow.cljs.build :as cljs]
            ))

(def css-packages
  [{:name "frontend"
    :modules ["src/css/frontend.scss"]
    :public-dir "public/assets/frontend/css"
    :public-path "/assets/frontend/css"}
   {:name "client"
    :modules ["src/css/client.scss"]
    :public-dir "public/assets/client/css"
    :public-path "/assets/client/css"}]

  )

(defn css [& args]
  (sass/build-packages css-packages))
