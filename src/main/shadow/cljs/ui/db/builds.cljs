(ns shadow.cljs.ui.db.builds
  (:require
    [shadow.grove.events :as ev]
    [shadow.cljs :as-alias m]
    [shadow.cljs.ui.db.relay-ws :as relay-ws]))

(defn forward-to-ws!
  {::ev/handle
   [::m/build-watch-compile!
    ::m/build-watch-stop!
    ::m/build-watch-start!
    ::m/build-compile!
    ::m/build-release!
    ::m/build-release-debug!]}
  [env {:keys [e build-id]}]
  (ev/queue-fx env
    :relay-send
    [{:op e
      :to 1 ;; FIXME: don't hardcode CLJ runtime id
      ::m/build-id build-id}]))

(defn active-builds [env]
  (->> (::m/build env)
       (vals)
       (filter ::m/build-worker-active)
       (sort-by ::m/build-id)
       (map ::m/build-id)
       (into [])))

(defn relay-sub-msg
  {::ev/handle ::m/sub-msg}
  [env {::m/keys [topic] :as msg}]
  (case topic
    ::m/build-status-update
    (let [{:keys [build-id build-status]} msg]
      (assoc-in env [::m/build build-id ::m/build-status] build-status))

    ::m/supervisor
    (let [{::m/keys [worker-op build-id]} msg]
      (case worker-op
        :worker-stop
        (assoc-in env [::m/build build-id ::m/build-worker-active] false)
        :worker-start
        (assoc-in env [::m/build build-id ::m/build-worker-active] true)

        (js/console.warn "unhandled supervisor msg" msg)))

    (do (js/console.warn "unhandled sub msg" msg)
        env)))