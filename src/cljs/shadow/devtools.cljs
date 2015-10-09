(ns shadow.devtools
  (:require-macros [shadow.devtools :as m])
  (:require [cognitect.transit :as transit]
            [cljs.core.async :as async]
            ))

(defonce custom-handlers (atom {}))

(goog-define enabled false)
(goog-define url "")
(goog-define before-load "")
(goog-define after-load "")

(defonce dump-chan (async/chan (async/dropping-buffer 10)))

(defn dump*
  "don't use directly, use dump macro"
  [title data]
  (let [w (transit/writer :json {:handlers @custom-handlers})
        s (transit/write w data)
        s (transit/write w {:title title
                            :data s})]

    (async/put! dump-chan s)
    #_ (xhr/send (str url "/msg") (fn [req] nil) "POST" s #js {"content-type" "text/plain"})
    ))

(defn register*
  "don't use directly, use register! macro"
  [type handler]
  (swap! custom-handlers assoc type handler))

