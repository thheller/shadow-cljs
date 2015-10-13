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

(defonce dump-chan
         (when ^boolean enabled
           (async/chan (async/sliding-buffer 10))))

(defn dump*
  "don't use directly, use dump macro"
  [title data]
  (async/put! dump-chan (pr-str {:title title
                                 :id (random-uuid)
                                 :data (pr-str data)}))
  (comment
    ;; transit doesn't seem to provide ability to set default handler
    (let [w (transit/writer :json {:handlers @custom-handlers})
          s (transit/write w data)
          s (transit/write w {:title title
                              :data s})]
      )))

(defn register*
  "don't use directly, use register! macro"
  [type handler]
  (swap! custom-handlers assoc type handler))

