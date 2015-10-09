(ns shadow.devtools.browser
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [cljs.reader :as reader]
            [cljs.core.async :as async]
            [shadow.devtools :as devtools]
            [goog.dom :as gdom]
            [goog.net.jsloader :as loader]
            [clojure.string :as str]))

(defonce *socket* (atom nil))

(defonce scripts-to-load (atom []))

(def debug)

(defn debug
  ([a1]
   (when (aget js/window "console" "debug")
     (.debug js/console a1)))
  ([a1 a2]
   (when (aget js/window "console" "debug")
     (.debug js/console a1 a2)))
  ([a1 a2 a3]
   (when (aget js/window "console" "debug")
     (.debug js/console a1 a2 a3))))

(def loaded? js/goog.isProvided_)

(defn load-scripts
  [filenames after-load-fn]
  (swap! scripts-to-load into filenames)

  (let [load-next
        (fn load-next []
          (if-let [next (first @scripts-to-load)]
            (do (swap! scripts-to-load (fn [remaining]
                                         ;; rest will result in () if nothing is left
                                         ;; we need to keep this a vector
                                         (into [] (rest remaining))))
                (debug "LOAD JS: " next)
                (-> (loader/load (str js/CLOSURE_BASE_PATH next "?r=" (rand)))
                    (.addBoth (fn []
                                (aset js/goog.included_ next true)
                                (load-next)))))
            (after-load-fn)))]
    (load-next)))


(defn handle-js-changes [js]
  (let [js-to-reload (->> js
                          ;; only reload things we actually require'd somewhere
                          (filter (fn [{:keys [provides]}]
                                    (some #(loaded? (str %)) provides)))
                          (into []))]

    (when (seq js-to-reload)
      (when devtools/before-load
        (let [fn (js/goog.getObjectByName devtools/before-load)]
          (debug "Executing :before-load" devtools/before-load)
          (fn)
          ))

      (let [after-load-fn (fn []
                            (when devtools/after-load
                              (let [fn (js/goog.getObjectByName devtools/after-load)]
                                (debug "Executing :after-load " devtools/after-load)
                                (fn))))]
        (load-scripts
          (map :js-name js-to-reload)
          after-load-fn)))))

(defn handle-css-changes [data]
  (doseq [[package-name package-info] data
          :let [{:keys [manifest path]} package-info]
          [css-name css-path] manifest]
    (when-let [node (js/document.querySelector (str "link[data-css-package=\"" package-name "\"][data-css-module=\"" css-name "\"]"))]
      (let [parent (gdom/getParentElement node)
            full-path (str path "/" css-path)
            new-link (doto (js/document.createElement "link")
                       (.setAttribute "rel" "stylesheet")
                       (.setAttribute "href" (str full-path "?r=" (rand)))
                       (.setAttribute "data-css-package" package-name)
                       (.setAttribute "data-css-module" css-name))]
        (debug (str "CSS: reload \"" full-path "\""))
        (gdom/insertSiblingAfter new-link node)
        (gdom/removeNode node)
        ))))

(defn repl-print [value]
  (js/console.log "repl-print" value)
  (pr-str value))

(defn socket-msg [msg]
  (if-let [s @*socket*]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(defn repl-invoke [msg]
  (let [data (:js msg)
        result {:id (:id msg)
                :type :repl/result}
        result
        (try
          (js/console.log "eval" data)

          ;; FIXME: I kinda want to remember each result so I can refer to it later
          ;; (swap! repl-state update :results assoc id result)
          (let [ret (js/eval data)]
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 ret)

            (try
              (assoc result
                :value (repl-print ret))
              (catch :default e
                (js/console.log "encoding of result failed" e ret)
                (assoc result :error "ENCODING FAILED"))))
          (catch :default e
            (set! *e e)
            (js/console.log "repl/invoke error" (pr-str msg) e)
            (assoc result :error (pr-str e))))]

    (socket-msg result)))

(defn repl-require [{:keys [js-sources reload] :as msg}]
  (load-scripts
    ;; don't load if already loaded
    ;; FIXME: handle reload (nil, :reload or :reload-all)
    (->> js-sources
         (remove #(aget js/goog.included_ %)))
    (fn []
      (js/console.log "repl-require finished"))))

(defn repl-init [{:keys [repl-state]}]
  (load-scripts
    ;; don't load if already loaded
    (->> (:repl-js-sources repl-state)
         (remove #(aget js/goog.included_ %)))
    (fn [] (js/console.log "repl init complete"))))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg}]
  (case type
    :js (handle-js-changes (:data msg))
    :css (handle-css-changes (:data msg))
    :repl/invoke (repl-invoke msg)
    :repl/require (repl-require msg)
    :repl/init (repl-init msg)))

(defn repl-connect []
  ;; FIXME: fallback for IE?
  (when (aget js/window "WebSocket")
    (let [socket (js/WebSocket. (str/replace devtools/url #"^http" "ws"))]

      (reset! *socket* socket)

      (set! (.-onmessage socket)
            (fn [e]
              (handle-message (-> e .-data (reader/read-string)))))

      (set! (.-onopen socket)
            (fn [e]
              ;; patch away the already declared exception
              (set! (.-provide js/goog) js/goog.constructNamespace_)
              (.log js/console "DEVTOOLS: connected!")))
      (set! (.-onclose socket)
            (fn [e]
              ;; not a big fan of reconnecting automatically since a disconnect
              ;; may signal a change of config, safer to just reload the page
              (.warn js/console "DEVTOOLS: disconnected! (reload page to reconnect)")
              (reset! *socket* nil)
              ))

      (set! (.-onerror socket)
            (fn [e]))

      )))

(defn dump-transmitter []
  (go (loop []
        (let [msg (<! devtools/dump-chan)]
          (when-not (nil? msg)
            (socket-msg {:type :devtools/dump
                         :value msg})
            (recur))
          ))))

(when ^boolean devtools/enabled
  (repl-connect)
  (dump-transmitter))
