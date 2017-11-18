(ns shadow.cljs.devtools.client.env
  (:require [goog.object :as gobj]
            [cljs.tools.reader :as reader]))

(defonce client-id (random-uuid))

(goog-define enabled false)

(goog-define autoload true)

(goog-define module-format "goog")

(goog-define before-load "")

(goog-define before-load-async "")

(goog-define after-load "")

(goog-define reload-with-state false)

(goog-define build-id "")

(goog-define proc-id "")

(goog-define repl-host "")

(goog-define repl-port 8200)

(goog-define use-document-host true)

(goog-define ssl false)

(defn ws-url [client-type]
  {:pre [(keyword? client-type)]}
  (let [host
        (if (and (= :browser client-type) use-document-host)
          js/document.location.hostname
          repl-host)]
    (str "ws" (when ssl "s") "://" host ":" repl-port "/worker/ws/" build-id "/" proc-id "/" client-id "/" (name client-type))))

(defn ws-listener-url [client-type]
  (let [host
        (if (and (= :browser client-type) use-document-host)
          js/document.location.hostname
          repl-host)]
    (str "ws" (when ssl "s") "://" host ":" repl-port "/worker/listener-ws/" build-id "/" proc-id "/" client-id)))

(defn files-url []
  (str "http" (when ssl "s") "://" repl-host ":" repl-port "/worker/files/" build-id "/" proc-id "/" client-id))

(def repl-print-fn pr-str)

(defn repl-call [repl-expr repl-error]
  (let [result {:type :repl/result}]
    (try
      (let [ret (repl-expr)]
        (set! *3 *2)
        (set! *2 *1)
        (set! *1 ret)

        (try
          (assoc result
            :value (repl-print-fn ret))
          (catch :default e
            (js/console.log "encoding of result failed" e ret)
            (assoc result :error "ENCODING FAILED"))))
      (catch :default e
        (set! *e e)
        (repl-error result e)
        ))))

(defn process-ws-msg [e handler]
  (binding [reader/*default-data-reader-fn*
            (fn [tag value]
              [:tagged-literal tag value])]
    (let [text
          (.-data e)

          msg
          (try
            (reader/read-string text)
            (catch :default e
              (js/console.warn "failed to parse websocket message" text e)))]
      (when msg
        (handler msg))
      )))

