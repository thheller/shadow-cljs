(ns shadow.devtools.server.system-bus
  (:require [clojure.core.async :as async]))

(defn svc? [x]
  (and (map? x) (::service x)))

(defn tap
  [{:keys [bus-mult] :as svc} chan]
  {:pre [(svc? svc)]}
  (async/tap bus-mult chan)
  chan)

(defn broadcast!
  [{:keys [bus-mult-chan] :as svc} msg]
  {:pre [(svc? svc)]}
  (async/>!! bus-mult-chan msg))

(defn sub
  ([svc topic sub-chan]
    (sub svc topic sub-chan true))
  ([{:keys [bus-pub] :as svc} topic sub-chan close?]
   {:pre [(svc? svc)
          (keyword? topic)]}
   (async/sub bus-pub topic sub-chan close?)))

(defn unsub
  [{:keys [bus-pub] :as svc} topic sub-chan]
  {:pre [(svc? svc)
         (keyword? topic)]}
  (async/unsub bus-pub topic sub-chan))

(defn publish!
  [{:keys [bus-pub-chan] :as svc} topic msg]
  {:pre [(svc? svc)
         (keyword? topic)
         (map? msg)]}
  (async/>!! bus-pub-chan (assoc msg ::topic topic)))

(defn start []
  (let [bus-mult-chan
        (async/chan)

        bus-mult
        (async/mult bus-mult-chan)

        bus-pub-chan
        (async/chan)

        bus-pub
        (async/pub bus-pub-chan ::topic)]

    {::service true
     :bus-mult-chan bus-mult-chan
     :bus-mult bus-mult
     :bus-pub-chan bus-pub-chan
     :bus-pub bus-pub}
    ))

(defn stop [{:keys [bus-mult-chan bus-pub-chan] :as svc}]
  {:pre [(svc? svc)]}
  (async/close! bus-mult-chan)
  (async/close! bus-pub-chan))
