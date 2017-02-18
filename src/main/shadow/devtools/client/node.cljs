(ns shadow.devtools.client.node
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [shadow.devtools.client.env :as env]
            [cljs.core.async :as async]
            [cljs.reader :as reader]))

(def WS (.. (js/require "websocket") -client))

(defn repl-invoke [msg])

(defn repl-set-ns [msg])

(defn repl-require [msg])

(defn build-success
  [{:keys [info] :as msg}]

  (let [{:keys [sources compiled]}
        info

        files-to-require
        (->> sources
             (filter (fn [{:keys [name]}]
                       (contains? compiled name)))
             (map :js-name)
             (into []))]

    (when (seq files-to-require)

      (when env/before-load
        (let [fn (js/goog.getObjectByName env/before-load)]
          (js/console.warn "Executing :before-load" env/before-load)
          (let [state (fn)]

            (doseq [src files-to-require]
              (prn [:hot-loading src])
              (js/global.CLOSURE_IMPORT_SCRIPT src))

            (when env/after-load
              (let [fn (js/goog.getObjectByName env/after-load)]
                (js/console.warn "Executing :after-load " env/after-load)
                (if-not env/reload-with-state
                  (fn)
                  (fn state))))))))
    ))

(defn process-message
  [{:keys [type] :as msg}]
  (prn [:ws-msg msg])
  (case type
    :repl/invoke
    (repl-invoke msg)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/require
    (repl-require msg)

    :build-success
    (build-success msg)

    ;; default
    (prn [:msg msg])
    ))

(defonce tcp-ref (volatile! nil))

(defn tcp-close []
  (when-some [tcp @tcp-ref]
    (.close tcp)
    (vreset! tcp-ref nil)))

(defn tcp-connect []
  (let [client (new WS)]

    (.on client "connectFailed"
      (fn [error]
        (js/console.log "REPL connect failed" error)))

    (.on client "connect"
      (fn [con]
        (js/console.log "REPL client connected!")

        (.on con "message"
          (fn [msg]
            (if (not= "utf8" (.-type msg))
              (js/console.warn "REPL client msg" msg)

              ;; expected msg format, just an edn string
              (-> (.-utf8Data msg)
                  (reader/read-string)
                  (process-message)))))

        (.on con "close"
          (fn []
            (js/console.log "REPL client close")
            ))

        (.on con "error"
          (fn [err]
            (js/console.log "REPL client error" err)))
        ))

    (let [url
          (str "ws://localhost:8200/ws/client/" env/proc-id "/node")]
      (js/console.log "REPL connect: " url)
      (.connect client url "foo"))))

(when env/enabled
  (tcp-close) ;; in case this is reloaded
  (tcp-connect))

