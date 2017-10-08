(ns demo.selfhost.host
  (:require [cognitect.transit :as transit]
            [cljs.env :as env]
            [cljs.js]
            [shadow.js]
            ))

;; these should be helper fns somewhere, worker has them too
(defn transit-read [txt]
  (let [r (transit/reader :json)]
    (transit/read r txt)))

(defn transit-write [obj]
  (let [r (transit/writer :json)]
    (transit/write r obj)))

(defn print-result [{:keys [error value] :as result}]
  (js/console.log "result" result)
  (set! (.-innerHTML (js/document.getElementById "dump")) value))

(def code
  "
(ns simpleexample.core
  (:require [clojure.string :as str]
            [reagent.core :as r]))

(defonce timer (r/atom (js/Date.)))

(defonce time-color (r/atom \"#f34\"))

(defonce time-updater (js/setInterval
                       #(reset! timer (js/Date.)) 1000))

(defn greeting [message]
  [:h1 message])

(defn clock []
  (let [time-str (-> @timer .toTimeString (str/split \" \") first)]
    [:div.example-clock
     {:style {:color @time-color}}
     time-str]))

(defn color-input []
  [:div.color-input
   \"Time color: \"
   [:input {:type \"text\"
            :value @time-color
            :on-change #(reset! time-color (-> % .-target .-value))}]])

(defn simple-example []
  [:div
   [greeting \"Hello world, it is now\"]
   [clock]
   [color-input]])

(r/render [simple-example] (js/document.getElementById \"app\"))")

(defn shadow-load [{:keys [type provides text] :as load-info}]
  ;; FIXME: should not eval if if provides are loaded
  (js/eval text))

(defn handle-worker [{:keys [action] :as in} out]
  (case action
    :eval
    (js/eval (:source in))

    :shadow-load
    (shadow-load (:load-info in))

    :ready
    (out {:action :compile
          :code code})))

(defn start []
  (let [w (js/Worker. "/worker/js/worker.js")

        write-fn
        (fn [obj]
          (.postMessage w (transit-write obj)))]

    (.addEventListener w "message"
      (fn [e]
        ;; (js/console.log "worker message" e)
        (let [msg (transit-read (.-data e))]
          (handle-worker msg write-fn))))))

(defn stop [])
