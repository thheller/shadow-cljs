(ns demo.reagent
  (:require [reagent.core :as r]))

(defonce timer (r/atom (js/Date.)))

(defonce time-updater (js/setInterval
                        #(reset! timer (js/Date.)) 1000))

(defn greeting [message]
  [:h1 message])

(defn clock []
  (let [time-str (-> @timer .toTimeString (clojure.string/split " ") first)]
    [:div.example-clock time-str]))

(defonce click-count (r/atom 0))

(defn clicker []
  [:div.color-input
   {:on-click #(swap! click-count inc)}
   (str "clicks:" @click-count)])

(defn simple-example []
  [:div
   [greeting "Hello world, it is now"]
   [clicker]
   [clock]])

(defn ^:export run []
  (r/render [simple-example]
    (js/document.getElementById "app")))

(run)