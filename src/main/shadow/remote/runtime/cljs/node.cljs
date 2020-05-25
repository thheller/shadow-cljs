(ns shadow.remote.runtime.cljs.node
  (:require
    [goog.object :as gobj]
    [cognitect.transit :as transit]
    ["ws" :as ws]
    ;; this will eventually replace shadow.cljs.devtools.client completely
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.node :as node]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.common :as common]
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.eval-support :as eval-support]))

(extend-type common/Runtime
  renv/IEvalJS
  (-eval-js [this code]
    (js/SHADOW_NODE_EVAL code))

  common/IHostSpecific
  (do-repl-invoke [this msg]
    (node/node-eval msg))

  (do-repl-require [this {:keys [sources reload-namespaces] :as msg} done error]
    (try
      (doseq [{:keys [provides output-name] :as src} sources]
        (when (or (not (node/is-loaded? output-name))
                  (some reload-namespaces provides))
          (node/closure-import output-name)))

      (done)
      (catch :default e
        (error e)))))

(defn start []
  (let [ws-url
        (str (env/get-ws-url-base) "/api/runtime?type=node&build-id=" env/build-id)

        socket
        (ws. ws-url)

        state-ref
        (atom (shared/init-state))

        send-fn
        (fn [msg]
          (.send socket msg))

        runtime
        (doto (common/Runtime. state-ref send-fn)
          (shared/add-defaults))

        obj-support
        (obj-support/start runtime)

        tap-support
        (tap-support/start runtime obj-support)

        eval-support
        (eval-support/start runtime obj-support)

        stop
        (fn []
          (eval-support/stop eval-support)
          (tap-support/stop tap-support)
          (obj-support/stop obj-support)
          (.close socket))]

    (reset! renv/runtime-ref
      {:runtime runtime
       :obj-support obj-support
       :tap-support tap-support
       :eval-support eval-support})

    (.on socket "message"
      (fn [data]
        (let [t (transit/reader :json)
              msg (transit/read t data)]

          (shared/process runtime msg))))

    (.on socket "open"
      (fn [e]
        ;; allow shared/process to send messages directly to relay
        ;; without being coupled to the implementation of exactly how
        ))

    (.on socket "close"
      (fn [e]
        (stop)))

    (.on socket "error"
      (fn [e]
        (js/console.warn "tap-socket error" e)
        (stop)
        ))))

;; want things to start when this ns is in :preloads
(when (pos? env/worker-rid)
  (start))
