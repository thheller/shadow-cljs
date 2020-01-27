(ns shadow.cljs.ui.worker.api-ws
  (:require
    [cljs.core.async :as async :refer (go <!)]
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]
    ))

(defmulti handle-ws (fn [env msg] (::m/op msg)) :default ::default)

(defmethod handle-ws ::default [env msg]
  (js/console.warn "unhandled api-ws" msg env)
  {})

(defmethod handle-ws ::m/sub-msg
  [{:keys [db] :as env} {::m/keys [topic] :as msg}]
  (case topic
    ::m/build-status-update
    (let [{:keys [build-id build-status]} msg
          build-ident (db/make-ident ::m/build build-id)]
      {:db (assoc-in db [build-ident ::m/build-status] build-status)})

    ::m/supervisor
    (let [{:keys [op build-id]} msg
          build-ident (db/make-ident ::m/build build-id)]
      (case op
        :worker-stop
        {:db (assoc-in db [build-ident ::m/build-worker-active] false)}
        :worker-start
        {:db (assoc-in db [build-ident ::m/build-worker-active] true)}

        (js/console.warn "unhandled supervisor msg" msg)))

    (do (js/console.warn "unhandled sub msg" msg)
        {})))

(sw/reg-event-fx env/app-ref ::m/api-ws
  []
  (fn [env {::m/keys [op] :as msg}]
    ;; (js/console.log ::api-ws op msg)
    (handle-ws env msg)))

(defn fx-to-ws
  [env build-id]
  {:api-ws
   [{::m/op (::sw/event-id env)
     ::m/build-id build-id}]})

(sw/reg-event-fx env/app-ref ::m/build-watch-compile! [] fx-to-ws)
(sw/reg-event-fx env/app-ref ::m/build-watch-stop! [] fx-to-ws)
(sw/reg-event-fx env/app-ref ::m/build-watch-start! [] fx-to-ws)
(sw/reg-event-fx env/app-ref ::m/build-compile! [] fx-to-ws)
(sw/reg-event-fx env/app-ref ::m/build-release! [] fx-to-ws)
(sw/reg-event-fx env/app-ref ::m/build-release-debug! [] fx-to-ws)

(defn send! [app-ref msg]
  (let [{::keys [out]} @app-ref]
    (when-not (async/offer! out msg)
      (js/console.warn "api-ws overload, dropped msg" msg))))

(sw/reg-fx env/app-ref :api-ws
  (fn [{::keys [ws-ref] ::sw/keys [transit-str] :as env} messages]
    (let [socket @ws-ref]
      (doseq [msg messages]
        (.send socket (transit-str msg))))))

(defn init [app-ref]
  (let [socket (js/WebSocket. (str "ws://" js/self.location.host "/api/ws"))
        ws-ref (atom socket)

        api-out
        (async/chan 100)]

    (swap! app-ref assoc ::ws-ref ws-ref ::socket socket ::out api-out)

    (let [{::sw/keys [^function transit-read ^function transit-str]} @app-ref]
      (.addEventListener socket "message"
        (fn [e]
          (let [{:keys [op] :as msg} (transit-read (.-data e))]
            (sw/tx* @env/app-ref [::m/api-ws msg]))))

      (.addEventListener socket "open"
        (fn [e]
          ;; (js/console.log "api-open" e socket)
          (go (loop []
                (when-some [msg (<! api-out)]
                  (.send socket (transit-str msg))
                  (recur)))))))

    (.addEventListener socket "close"
      (fn [e]
        (js/console.log "api-close" e)))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "api-error" e)))))
