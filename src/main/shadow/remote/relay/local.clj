(ns shadow.remote.relay.local
  (:require
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [shadow.remote.relay.api :as rapi]
    [shadow.remote.relay.simple-query :as squery]
    [shadow.jvm-log :as log]
    [clojure.set :as set])
  (:import [java.util Date]))

;; FIXME: figure out what to do about clients that don't keep up
;; should use a blocking send since one slow client should hold up all other clients
;; each send occurs in the client-receive thread
;; if other clients can't keep up could close :stop to stop the other clients loop
;; or should it just use a dropping/sliding buffer?
;; maybe let clients provide the :to channel as well so they can decide?
(defn relay-send [relay {:keys [to stop] :as client} msg]
  (when-not (async/offer! to msg)
    ;; for now just warn to see how often this happens
    (log/warn ::client-not-keeping-up {:client (dissoc client :from :to) :msg msg})))

(defn relay-client-receive [relay client msg]
  ;; just for easier debugging, return value ignored
  )

(defn relay-client-start [relay client]
  ;; just for easier debugging, return value ignored
  )

(defn relay-client-stop [relay client]
  ;; just for easier debugging, return value ignored
  )

(defn maybe-add-call-id [{:keys [call-id] :as req} res]
  (cond-> res
    call-id
    (assoc :call-id call-id)))

(defmulti handle-sys-msg
  ;; origin is either a runtime or a tool
  (fn [relay origin msg] (:op msg ::default))
  :default ::default)

(defmethod handle-sys-msg ::default
  [{:keys [state-ref] :as relay} origin {:keys [op] :as msg}]
  (relay-send relay origin (maybe-add-call-id msg {:op :unknown-relay-op :msg msg})))

(defn send-to-clients
  ;; from relay to clients
  ([{:keys [state-ref] :as relay} targets msg]
   (doseq [to targets]
     (let [target (get-in @state-ref [:clients to])]
       (if (or (not target)
               (not (:handshake-completed target)))
         ::ignore-for-now
         (relay-send relay target msg)))))

  ;; from client to client
  ([{:keys [state-ref] :as relay} targets {from-id :client-id :as from} msg]
   ;; FIXME: should this fail completely if not all targets are valid?
   (doseq [to targets]
     (let [target (get-in @state-ref [:clients to])]
       (if (or (not target)
               (not (:handshake-completed target)))
         (relay-send relay from
           (maybe-add-call-id msg {:op :client-not-found
                                   :client-id to}))

         (relay-send relay target
           (-> msg
               (assoc :from from-id)
               (dissoc :to))))))))

(defn notify-clients
  [{:keys [state-ref] :as relay} trigger-id client-info event-op msg-data]
  (doseq [[client-id client] (:clients @state-ref)
          :when (:handshake-completed client)
          :when (not= client-id trigger-id)
          [notify-op query] (:queries client)
          :when (squery/query {} (assoc client-info :client-id trigger-id) query)]

    (relay-send relay client
      (assoc msg-data
        :op notify-op
        :event-op event-op))))

(defn handle-client-disconnect
  [{:keys [state-ref] :as relay}
   {disconnect-id :client-id :as origin}]
  (let [{:keys [handshake-completed client-info]} (get-in @state-ref [:clients disconnect-id])]
    (swap! state-ref update :clients dissoc disconnect-id)

    (log/debug ::client-disconnect {:client-id disconnect-id :client-info client-info})

    ;; don't notify if handshake never completed
    (when handshake-completed
      (notify-clients
        relay
        disconnect-id
        client-info
        :client-disconnect
        {:client-id disconnect-id}))))

(defn handle-client-msg
  [{:keys [state-ref] :as relay}
   {:keys [client-id] :as origin}
   {:keys [op to] :as msg}]
  (if (not op)
    (do (log/warn ::client-sent-invalid-msg {:client-id client-id
                                             :msg msg})
        (relay-send relay origin {:op :invalid-msg :msg msg}))

    (let [{:keys [handshake-completed] :as client}
          (get-in @state-ref [:clients client-id])]

      (if (and (not handshake-completed)
               (not (= :hello (:op msg))))

        (do (log/warn ::handshake-not-completed {:client-id client-id :msg msg})
            (relay-send relay origin {:op :expected-hello :got msg})
            (async/close! (:to origin)))

        ;; handshake or handshake completed
        (cond
          ;; messages without :to are handled by relay
          (not (contains? msg :to))
          (handle-sys-msg relay origin msg)

          (nil? to)
          (do (log/warn ::client-sent-invalid-msg {:client-id client-id
                                                   :msg msg})
              (relay-send relay origin {:op :invalid-msg :msg msg}))

          ;; :to 1
          (number? to)
          (send-to-clients relay [to] origin msg)

          ;; :to #{1 2}, :to [1 2], :to (1 2)
          (and (coll? to) (or (sequential? to) (set? to)) (every? number? to))
          (send-to-clients relay to origin msg)

          :else
          (log/warn ::dropped-message {:from origin :msg msg}))))))

(defrecord LocalRelay [id-seq-ref state-ref]
  rapi/IRelayClient
  (connect [relay from-client connect-info]
    (let [client-id (swap! id-seq-ref inc)

          to-client
          (async/chan 256)

          stop
          (async/chan)

          client-data
          {:client-info {:connection-info connect-info
                         :since (Date.)}
           :client-id client-id
           :stop stop
           :from from-client
           :to to-client}]

      (log/debug ::client-connect {:client-id client-id :info connect-info})

      (swap! state-ref assoc-in [:clients client-id] client-data)

      ;; keep each client in its own thread, easier to deal with messaging
      (async/thread
        (relay-client-start relay client-data)

        ;; send this first. at this point the client is welcome
        ;; connect should not be called for clients that aren't welcome
        ;; but welcome is the first message so websockets can decide to send
        ;; a not-welcome message first and never register with the relay
        ;; client must answer with :hello before sending any other message though

        (relay-send relay client-data {:op :welcome :client-id client-id})
        (loop []
          (async/alt!!
            stop
            ([_] ::stop)

            from-client
            ([msg]
             (when (some? msg)
               (relay-client-receive relay client-data msg)

               (try
                 (handle-client-msg relay client-data msg)
                 (catch Exception e
                   ;; FIXME: should it disconnect the client?
                   (log/warn-ex e ::relay-client-ex (get-in @state-ref [:clients client-id]))))

               (recur)))))

        (handle-client-disconnect relay client-data)

        (relay-client-stop relay client-data)
        (async/close! to-client))

      to-client)))

(defn start []
  (LocalRelay.
    (atom 0)
    (atom {:clients {}})))

(defn stop [{:keys [state-ref] :as svc}]
  (let [{:keys [clients]} @state-ref]
    (doseq [{:keys [to]} (vals clients)]
      (async/close! to))))

(defmethod handle-sys-msg :hello
  [{:keys [state-ref] :as relay}
   {:keys [client-id] :as origin} {:keys [client-info] :as msg}]
  (if-not (map? client-info)
    (do (swap! state-ref update :clients dissoc client-id)
        (relay-send relay origin {:op :missing-client-info :msg msg})
        (async/close! (:to origin)))
    (do (swap! state-ref update-in [:clients client-id]
          (fn [current]
            (-> current
                (assoc :handshake-completed true)
                (update :client-info merge client-info))))

        (let [client-info (get-in @state-ref [:clients client-id :client-info])]
          (notify-clients
            relay
            client-id
            client-info
            :client-connect
            {:client-id client-id
             :client-info client-info})))))

(defmethod handle-sys-msg :unknown-op
  [{:keys [state-ref]}
   {:keys [client-id] :as origin} msg]
  (log/warn ::client-sent-unknown-op
    {:client-id client-id
     :msg msg}))

(defmethod handle-sys-msg :request-clients
  [{:keys [state-ref] :as relay}
   {:keys [client-id] :as origin}
   {:keys [query notify notify-op]
    :or {notify-op :notify}
    :as msg}]
  (let [{:keys [clients]} @state-ref
        result (->> clients
                    (vals)
                    (filter
                      (fn [{:keys [client-id client-info]}]
                        (squery/query {} (assoc client-info :client-id client-id) query)))
                    (map (fn [{:keys [client-id client-info]}]
                           {:client-id client-id
                            :client-info client-info}))
                    (vec))]

    ;; so client doesn't have to send two messages which may lead to races
    ;; since there might be :request-clients :new-runtime :request-notify
    ;; or :request-notify :new-runtime :request-clients
    ;; leading to at least partial duplication, doesn't matter much but easier on the user
    ;; to not have to worry about it
    (when notify
      (swap! state-ref assoc-in [:clients client-id :queries notify-op] query))

    (relay-send relay origin
      (maybe-add-call-id msg {:op :clients
                              :clients result}))))

;; origin wants to be notified when clients connect or disconnect
;; optional matching query, optional custom :query-id for messages
(defmethod handle-sys-msg :request-notify
  [{:keys [state-ref]}
   {:keys [client-id] :as origin}
   {:keys [query notify-op]
    :or {notify-op :notify}
    :as msg}]
  ;; FIXME: should this ACK? probably ok not to
  (swap! state-ref assoc-in [:clients client-id :queries notify-op] query))

(comment
  (def svc (start))
  (def svc (:relay (shadow.cljs.devtools.server.runtime/get-instance)))

  svc

  (stop svc)

  (def tool-in (async/chan))
  (def tool-out (rapi/connect svc tool-in {}))

  (def tid-ref (atom nil))

  (go (loop []
        (when-some [msg (<! tool-out)]
          (prn :tool-out)
          (clojure.pprint/pprint msg)
          (when (= :welcome (:op :msg))
            (reset! tid-ref (:client-id msg)))
          (recur)))
      (prn :tool-out-shutdown))

  (def runtime-in (async/chan))
  (def runtime-out (rapi/connect svc runtime-in {}))

  (go (loop []
        (when-some [msg (<! runtime-out)]
          (prn :runtime-out)
          (clojure.pprint/pprint msg)
          (recur)))
      (prn :runtime-out-shutdown))

  (>!! tool-in {:op :hello :client-info {:type :tool}})
  (>!! runtime-in {:op :hello :client-info {:type :runtime}})

  (>!! tool-in {:op :request-clients})

  (>!! tool-in {:op :clj-eval
                :to 1
                :input {:code "(+ 1 2)"
                        :ns 'user}})

  (>!! tool-in {:op :obj-request
                :to 1
                :request-op :edn
                :oid "dbf3d3a5-aeed-4ba9-a1f7-b8f17eb32e12"})

  (require '[shadow.remote.runtime.clj.local :as clj])

  (def clj (clj/start svc))
  (def clj (:clj-runtime (shadow.cljs.devtools.server.runtime/get-instance)))

  clj

  (clj/stop clj)

  (>!! tool-in {:op :request-clients})

  (def test-runtime-id 2)

  (>!! tool-in {:op :request-supported-ops :to test-runtime-id})
  (>!! tool-in {:op :request-tap-history
                :num 10
                :to (-> clj :state-ref deref :client-id)})


  (tap> {:tap 1})
  (>!! tool-in {:op :tap-subscribe :to (-> clj :state-ref deref :client-id)})
  (>!! tool-in {:op :tap-unsubscribe :to (-> clj :state-ref deref :client-id)})
  (tap> {:tap 1})

  (>!! tool-in {:op :tap-subscribe :to 7})

  (async/close! tool-in)
  (async/close! runtime-in)
  (async/close! runtime-out)

  svc)