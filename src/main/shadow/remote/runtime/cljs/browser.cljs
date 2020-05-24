(ns shadow.remote.runtime.cljs.browser
  (:require
    [cognitect.transit :as transit]
    ;; this will eventually replace shadow.cljs.devtools.client completely
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.browser :as browser]
    [goog.net.XhrIo :as xhr]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.eval-support :as eval-support]
    [cljs.reader :as reader]))

(defn transit-read [data]
  (let [t (transit/reader :json)]
    (transit/read t data)))

(defn transit-str [obj]
  (let [w (transit/writer :json)]
    (transit/write w obj)))

(declare interpret-actions)

(defn continue! [state]
  (interpret-actions state))

(defn abort! [{:keys [callback] :as state} action ex]
  (-> state
      (assoc :result :runtime-error
             :ex ex
             :ex-action action)
      (dissoc :runtime :callback)
      (callback)))

(defn interpret-action [{:keys [^BrowserRuntime runtime] :as state} {:keys [type] :as action}]
  (case type
    :repl/set-ns
    (-> state
        (assoc :ns (:ns action))
        (continue!))

    :repl/require
    (let [{:keys [warnings internal]} action]
      (.do-repl-require runtime action
        (fn [sources]
          (-> state
              (update :loaded-sources into sources)
              (update :warnings into warnings)
              (cond->
                (not internal)
                (update :results conj nil))
              (continue!)))
        (fn [ex]
          (abort! state action ex))))

    :repl/invoke
    (let [{:keys [js]} action]
      (try
        (let [res (renv/eval-js runtime js)]
          (-> state
              (update :results conj res)
              (continue!)))
        (catch :default ex
          (abort! state action ex))))

    ;; did I forget any?
    (throw (ex-info "unhandled repl action" {:state state :action action}))))

(defn interpret-actions [{:keys [queue] :as state}]
  (if (empty? queue)
    (let [{:keys [callback]} state]
      (-> state
          (dissoc :runtime :callback :queue)
          (callback)))

    (let [action (first queue)
          state (update state :queue rest)]
      (interpret-action state action))))

(defrecord BrowserRuntime [ws state-ref]
  api/IRuntime
  (relay-msg [runtime msg]
    (.send ws (transit-str msg)))
  (add-extension [runtime key spec]
    (shared/add-extension runtime key spec))
  (del-extension [runtime key]
    (shared/del-extension runtime key))

  renv/IEvalJS
  (-eval-js [this code]
    (js* "(0,eval)(~{})" code))

  renv/IEvalCLJS
  (-eval-cljs [this input callback]
    ;; FIXME: define what input is supposed to look like
    ;; {:code "(some-cljs)" :ns foo.bar}
    ;; FIXME: transit?
    (xhr/send
      (str (env/get-url-base) "/worker/compile/" env/build-id "/" env/proc-id "/browser")
      (fn [res]
        (this-as ^goog req
          (let [{:keys [type] :as result}
                (transit-read (.getResponseText req))]

            (case type
              ;; compile failed
              :repl/error
              (callback
                {:result :compile-error
                 :report (:report result)})

              :repl/actions
              (interpret-actions
                {:runtime this
                 :callback callback
                 :input input
                 :actions (:actions result)
                 :queue (:actions result)
                 :ns (:ns input)
                 :result :ok
                 :results []
                 :warnings []
                 :loaded-sources []})

              (js/console.error "Unhandled compiled result" result)))))
      "POST"
      (transit-str input)
      #js {"content-type" "application/transit+json; charset=utf-8"}))

  Object
  (do-repl-require [this {:keys [sources reload-namespaces js-requires] :as msg} done error]
    (let [sources-to-load
          (->> sources
               (remove (fn [{:keys [provides] :as src}]
                         (and (env/src-is-loaded? src)
                              (not (some reload-namespaces provides)))))
               (into []))]

      (browser/load-sources
        sources-to-load
        (fn [sources]
          (try
            (browser/do-js-load sources)
            (when (seq js-requires)
              (browser/do-js-requires js-requires))
            (done sources-to-load)
            (catch :default ex
              (error ex))))))))

(defn start []
  (if-some [{:keys [stop]} @renv/runtime-ref]
    ;; if already connected. cleanup and call restart async
    ;; need to give the websocket a chance to close
    ;; only need this to support hot-reload this code
    ;; can't use :dev/before-load-async hooks since they always run
    (do (stop)
        (reset! renv/runtime-ref nil)
        (js/setTimeout start 10))

    (let [ws-url (str (env/get-ws-url-base) "/api/runtime"
                      (if (exists? js/document)
                        "?type=browser"
                        "?type=browser-worker")
                      "&build-id=" (js/encodeURIComponent env/build-id))
          socket (js/WebSocket. ws-url)

          state-ref
          (atom {})

          runtime
          (doto (BrowserRuntime. socket state-ref)
            (shared/add-defaults))

          obj-support
          (obj-support/start runtime)

          tap-support
          (tap-support/start runtime obj-support)

          eval-support
          (eval-support/start runtime obj-support)

          stop
          (fn []
            (tap-support/stop tap-support)
            (obj-support/stop obj-support)
            (eval-support/stop eval-support)
            (.close socket))]

      (renv/init-runtime!
        {:runtime runtime
         :obj-support obj-support
         :tap-support tap-support
         :eval-support eval-support
         :stop stop})

      (.addEventListener socket "message"
        (fn [e]
          (shared/process runtime (transit-read (.-data e)))
          ))

      (.addEventListener socket "open"
        (fn [e]
          ;; allow shared/process to send messages directly to relay
          ;; without being coupled to the implementation of exactly how
          ))

      (.addEventListener socket "close"
        (fn [e]
          (stop)))

      (.addEventListener socket "error"
        (fn [e]
          (js/console.warn "tap-socket error" e)
          (stop)
          )))))

;; want things to start when this ns is in :preloads
(start)
