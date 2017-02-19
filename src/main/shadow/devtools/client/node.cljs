(ns shadow.devtools.client.node
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [shadow.devtools.client.env :as env]
            [cljs.core.async :as async]
            [cljs.reader :as reader]
            [goog.object :as gobj]))

(def WS (.. (js/require "websocket") -client))

(def VM (js/require "vm"))

(defonce client-id (random-uuid))

(defonce ws-ref (volatile! nil))

(defn ws-close []
  (when-some [tcp @ws-ref]
    (.close tcp)
    (vreset! ws-ref nil)))

(defn ws-msg [msg]

  (when-some [ws @ws-ref]
    (.sendUTF ws (pr-str msg)
      (fn [err]
        (when err
          (js/console.error "REPL msg send failed" err))))
    ))

(defn repl-error [result e]
  (js/console.error "eval error" e)
  result)

(defn node-eval [code]
  (let [result (js/global.NODE_EVAL code)]
    result))

(defn closure-import [src]
  (gobj/set js/goog.dependencies_.written src true)
  (js/global.CLOSURE_IMPORT_SCRIPT src))

(defn repl-init
  [{:keys [repl-state] :as msg}]
  (let [{:keys [repl-js-sources]}
        repl-state]

    (doseq [js repl-js-sources
            :when (not (env/goog-is-loaded? js))]
      (closure-import js)
      )))

(defn repl-invoke [{:keys [id js] :as msg}]
  (let [result
        (-> (env/repl-call #(node-eval js) pr-str repl-error)
            (assoc :id id))]

    (ws-msg result)))

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
          (js/console.warn "REPL before-load" env/before-load)
          (let [state (fn)]

            (doseq [src files-to-require]
              (js/console.info "REPL reloading: " src)
              (closure-import src))

            (when env/after-load
              (let [fn (js/goog.getObjectByName env/after-load)]
                (js/console.warn "REPL after-load " env/after-load)
                (if-not env/reload-with-state
                  (fn)
                  (fn state))))))))
    ))

(defn process-message
  [{:keys [type] :as msg}]
  (case type
    :repl/init
    (repl-init msg)

    :repl/invoke
    (repl-invoke msg)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/require
    (repl-require msg)

    :build-success
    (build-success msg)

    ;; default
    (prn [:repl-unknown msg])
    ))

(defn ws-connect []
  (let [client (new WS)]

    (.on client "connectFailed"
      (fn [error]
        (js/console.log "REPL connect failed" error)))

    (.on client "connect"
      (fn [con]
        (vreset! ws-ref con)
        (js/console.log "REPL client connected!")

        (.on con "message"
          (fn [msg]
            (if (not= "utf8" (.-type msg))
              (js/console.warn "REPL unknown client msg" msg)

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

    (let [url (env/ws-url :node)]
      (js/console.log "REPL connecting: " url)
      (.connect client url "foo"))))

(when env/enabled
  (ws-close) ;; if this is reloaded, reconnect the socket
  (ws-connect))

