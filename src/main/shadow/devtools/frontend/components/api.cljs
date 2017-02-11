(ns shadow.devtools.frontend.components.api
  (:require [shadow.xhr :as xhr]
            [cognitect.transit :as transit]))

(xhr/register-transform
  "application/transit+json"
  (fn [text]
    (let [r (transit/reader :json {:handlers {#_ "f" #_ (fn [v] (data/bigdec v))}})]
      (transit/read r text)
      )))

(defn load-builds []
  (xhr/chan :GET "/api/builds" nil {:body-only true}))
