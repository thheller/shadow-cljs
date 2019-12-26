(ns shadow.cljs.devtools.server.system-bus
  (:require
    [shadow.cljs.model :as m]
    [clojure.core.async :as async]))

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
  (when-not (async/offer! bus-mult-chan msg)
    (throw (ex-info "failed to broadcast! message, offer! failed" {:msg msg}))))

(defn sub
  ([svc topic sub-chan]
   (sub svc topic sub-chan true))
  ([{:keys [bus-pub] :as svc} topic sub-chan close?]
   {:pre [(svc? svc)]}
   (async/sub bus-pub topic sub-chan close?)))

(defn unsub
  [{:keys [bus-pub] :as svc} topic sub-chan]
  {:pre [(svc? svc)]}
  (async/unsub bus-pub topic sub-chan))

(defn publish!
  [{:keys [bus-pub-chan] :as svc} topic msg]
  {:pre [(svc? svc)
         (map? msg)]}
  (when-not (async/offer! bus-pub-chan (assoc msg ::m/topic topic))
    (throw (ex-info "failed to publish!, offer! failed" {:msg msg :topic topic}))))

(defn start []
  (let [bus-mult-chan
        (async/chan 1000)

        bus-mult
        (async/mult bus-mult-chan)

        bus-pub-chan
        (async/chan 1000)

        bus-pub
        (async/pub bus-pub-chan ::m/topic)]

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
