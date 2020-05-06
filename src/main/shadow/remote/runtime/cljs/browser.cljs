(ns shadow.remote.runtime.cljs.browser
  (:require
    [cognitect.transit :as transit]
    ;; this will eventually replace shadow.cljs.devtools.client completely
    [shadow.cljs.devtools.client.env :as env]
    [goog.net.XhrIo :as xhr]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.eval-support :as eval-support]
    ))

(defn transit-read [data]
  (let [t (transit/reader :json)]
    (transit/read t data)))

(defn transit-str [obj]
  (let [w (transit/writer :json)]
    (transit/write w obj)))

(declare interpret-actions)

(defn abort! [{:keys [callback] :as state} action ex]
  (-> state
      (assoc :failed true
             :completed false
             :ex ex
             :ex-action action)
      (callback)))

(defn interpret-action [{:keys [^BrowserRuntime runtime] :as state} {:keys [type] :as action}]
  (case type
    :repl/invoke
    (let [{:keys [js]} action]
      (try
        (let [res (.eval-js runtime js)]
          (-> state
              (update :eval-results conj {:value res :action action})
              (interpret-actions)))
        (catch :default ex
          (abort! state action ex))))))

(defn interpret-actions [{:keys [actions] :as state}]
  (if (empty? actions)
    ((:callback state) state)
    (let [{:keys [type] :as action} (first actions)
          state (update state :actions rest)]
      (interpret-action state action))))

(defrecord BrowserRuntime [ws state-ref]
  api/IRuntime
  (relay-msg [runtime msg]
    (.send ws (transit-str msg)))
  (add-extension [runtime key spec]
    (shared/add-extension runtime key spec))
  (del-extension [runtime key]
    (shared/del-extension runtime key))

  Object
  (eval-js [this code]
    (js* "(0,eval)(~{})" code))

  (eval-cljs [this msg callback]
    ;; FIXME: define that msg is supposed to look like
    ;; {:code "(some-cljs)" :ns foo.bar}
    ;; FIXME: transit?
    (xhr/send
      (str (env/get-url-base) "/worker/compile/" env/build-id "/" env/proc-id "/browser")
      (fn [res]
        (this-as ^goog req
          (let [{:keys [type] :as result}
                (transit-read (.getResponseText req))]

            (if-not (= :repl/actions type)
              (callback {:failed true :result result})
              (interpret-actions {:runtime this
                                  :callback callback
                                  :input msg
                                  :actions (:actions result)
                                  :eval-results []
                                  :errors []})))))
      "POST"
      (transit-str msg)
      #js {"content-type" "application/transit+json; charset=utf-8"})))

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
