(ns shadow.cljs.devtools.client.env
  (:require [goog.object :as gobj]
            [cljs.tools.reader :as reader]
            [clojure.string :as str]))

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

(goog-define devtools-url "")

(goog-define ssl false)

(defn get-repl-host []
  (if (and use-document-host js/goog.global.document)
    js/document.location.hostname
    repl-host))

(defn get-url-base []
  (if (seq devtools-url)
    devtools-url
    (str "http" (when ssl "s") "://" (get-repl-host) ":" repl-port)))

(defn get-ws-url-base []
  (-> (get-url-base)
      (str/replace #"^http" "ws")))

(defn ws-url [client-type]
  {:pre [(keyword? client-type)]}
  (str (get-ws-url-base) "/ws/worker/" build-id "/" proc-id "/" client-id "/" (name client-type)))

(defn ws-listener-url [client-type]
  (str (get-ws-url-base) "/ws/listener/" build-id "/" proc-id "/" client-id))

(defn files-url []
  (str (get-url-base) "/worker/files/" build-id "/" proc-id "/" client-id))

(def repl-print-fn pr-str)

(defn repl-error [result e]
  (-> result
      (assoc :error (.-message e)
             :ex-data (ex-data e))
      (cond->
        (.hasOwnProperty e "stack")
        (assoc :stack (.-stack e)))))

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

