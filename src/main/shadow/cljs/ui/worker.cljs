(ns shadow.cljs.ui.worker
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.http-fx :as http-fx]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]
    [shadow.cljs.ui.worker.relay-ws :as relay-ws]
    [shadow.cljs.ui.worker.generic]
    [shadow.cljs.ui.worker.builds]
    [shadow.cljs.ui.worker.inspect]
    ))

(defn ^:dev/after-load after-load []
  (sw/refresh-all-queries! env/app-ref))

(defn init []
  ;; FIXME: this needs to be better, feels kinda ugly and clunky
  (sw/init! env/app-ref)
  (sw/reg-fx env/app-ref :graph-api
    (http-fx/make-handler
      {:on-error [::m/request-error!]
       :base-url "/api/graph"
       :request-format :transit}))

  (sw/stream-setup env/app-ref ::m/taps {:capacity 1000})

  (relay-ws/init env/app-ref
    (fn []
      ;; builds starting, stopping
      (relay-ws/cast! @env/app-ref
        {:op ::m/subscribe
         :to 1 ;; FIXME: don't blindly assume CLJ runtime is 1
         ::m/topic ::m/supervisor})

      ;; build progress, errors, success
      (relay-ws/cast! @env/app-ref
        {:op ::m/subscribe
         :to 1
         ::m/topic ::m/build-status-update})))

  (sw/run-tx @env/app-ref [::m/init!])

  ;; timing problem with tap> maybe not being initialized yet
  (js/setTimeout #(tap> env/app-ref) 100))
