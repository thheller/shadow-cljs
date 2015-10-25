(ns shadow.devtools.browser
  (:require-macros [cljs.core.async.macros :refer (go alt!)])
  (:require [cljs.reader :as reader]
            [cljs.core.async :as async]
            [shadow.devtools :as devtools]
            [goog.dom :as gdom]
            [goog.net.jsloader :as loader]
            [goog.userAgent.product :as product]
            [clojure.string :as str]
            [goog.Uri]))

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

(defn goog-is-loaded? [name]
  (js/goog.object.get js/goog.included_ name))

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
                (debug "LOAD JS:" next)
                (-> (loader/load (str js/CLOSURE_BASE_PATH next "?r=" (rand)))
                    (.addBoth (fn []
                                (aset js/goog.included_ next true)
                                (load-next)))))
            (after-load-fn)))]
    (load-next)))

(defn module-is-active? [module]
  (js/goog.object.get js/SHADOW_MODULES (str module)))

(defn handle-js-reload [{:keys [sources reload] :as msg}]
  ;; reload is a set of js-names that should be reloaded
  (let [reload (->> reload
                    ;; skip those not already loaded
                    ;; in a multi module project a page might not have loaded one module
                    ;; this should not start loading it
                    (filter goog-is-loaded?)
                    (into #{}))]

    ;; if none of the files we are required to RE-load where loaded, do nothing
    (when (seq reload)
      (let [js-to-reload (->> sources
                              ;; only load sources not yet loaded or specifically told to reload
                              ;; not-yet-loaded in case of a new dependency added via (ns ...)
                              ;; but only if the module it belongs to is active
                              (filter (fn [{:keys [js-name module] :as src}]
                                        (and (module-is-active? module)
                                             (or (contains? reload js-name)
                                                 (not (goog-is-loaded? js-name))))))
                              (map :js-name)
                              (into []))]

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
            js-to-reload
            after-load-fn))))))

(defn handle-css-changes [{:keys [public-path name manifest] :as pkg}]
  (doseq [[css-name css-file-name] manifest]
    (when-let [node (js/document.querySelector (str "link[data-css-package=\"" name "\"][data-css-module=\"" css-name "\"]"))]
      (let [full-path (str public-path "/" css-file-name)
            new-link (doto (js/document.createElement "link")
                       (.setAttribute "rel" "stylesheet")
                       (.setAttribute "href" (str full-path "?r=" (rand)))
                       (.setAttribute "data-css-package" name)
                       (.setAttribute "data-css-module" css-name))]
        (debug "LOAD CSS:" full-path)
        (gdom/insertSiblingAfter new-link node)
        (gdom/removeNode node)
        ))))

(defn repl-print [value]
  ;; (js/console.log "repl-print" value)
  (pr-str value))

(defn socket-msg [msg]
  (if-let [s @*socket*]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))


;; from https://github.com/clojure/clojurescript/blob/master/src/main/cljs/clojure/browser/repl.cljs
;; I don't want to pull in all its other dependencies just for this function
(defn get-ua-product []
  (cond
    product/SAFARI :safari
    product/CHROME :chrome
    product/FIREFOX :firefox
    product/IE :ie))

(defn get-asset-root []
  (let [loc (js/goog.Uri. js/document.location.href)
        cbp (js/goog.Uri. js/CLOSURE_BASE_PATH)
        s (.toString (.resolve loc cbp))]
    ;; FIXME: stacktrace starts with file:/// but resolve returns file:/
    ;; how does this look on windows?
    (str/replace s #"^file:/" "file:///")
    ))

(defn repl-call [handler]
  (let [result {:type :repl/result}]
    (try
      (let [ret (handler)]
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
        (js/console.log "repl/invoke error" e)
        (assoc result
          :ua-product (get-ua-product)
          :error (str e)
          :asset-root (get-asset-root)
          :stacktrace (if (.hasOwnProperty e "stack")
                        (.-stack e)
                        "No stacktrace available."))))))

(defn repl-invoke [{:keys [id js]}]
  (let [result (repl-call #(js/eval js))]
    (-> result
        (assoc :id id)
        (socket-msg))))



(defn repl-require [{:keys [js-sources reload] :as msg}]
  (load-scripts
    (cond
      (= :reload reload)
      (let [all (butlast js-sources)
            self (last js-sources)]
        (-> (into [] (remove goog-is-loaded? all))
            (conj self)))

      (= :reload-all reload)
      js-sources

      :else
      (remove goog-is-loaded? js-sources))
    (fn []
      (js/console.log "repl-require finished"))))

(defn repl-init [{:keys [repl-state]}]
  (load-scripts
    ;; don't load if already loaded
    (->> (:repl-js-sources repl-state)
         (remove goog-is-loaded?))
    (fn [] (js/console.log "repl init complete"))))

(defn repl-set-ns [{:keys [ns]}]
  (js/console.log "repl/set-ns" (str ns)))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg}]
  (case type
    :js/reload (handle-js-reload msg)
    :css/reload (handle-css-changes msg)
    :repl/invoke (repl-invoke msg)
    :repl/require (repl-require msg)
    :repl/set-ns (repl-set-ns msg)
    :repl/init (repl-init msg)))

(defonce *dump-loop* (atom nil))

(defn dump-transmitter []
  ;; ensure there is only one dumper running
  (when-let [l @*dump-loop*]
    (async/close! l)
    (reset! *dump-loop* nil))

  (let [dump-loop (async/chan)]
    (reset! *dump-loop* dump-loop)
    (go (loop []
          (alt!
            dump-loop
            ([v]
              (when-not (nil? v)
                (recur)))

            devtools/dump-chan
            ([msg]
              (when-not (nil? msg)
                (socket-msg {:type :devtools/dump
                             :value msg})
                (recur))
              ))))))

(defn repl-connect []
  ;; FIXME: fallback for IE?
  (when (aget js/window "WebSocket")
    (let [print-fn cljs.core/*print-fn*
          socket (js/WebSocket. (-> devtools/url
                                    (str/replace #"^http" "ws")
                                    (str "/" (random-uuid) "/browser")))]



      (reset! *socket* socket)

      (set! (.-onmessage socket)
            (fn [e]
              (set-print-fn! (fn [& args]
                               (socket-msg {:type :repl/out
                                            :out (into [] args)})
                               (apply print-fn args)))

              (handle-message (-> e .-data (reader/read-string)))))

      (set! (.-onopen socket)
            (fn [e]
              ;; patch away the already declared exception
              (set! (.-provide js/goog) js/goog.constructNamespace_)
              (.log js/console "DEVTOOLS: connected!")
              (dump-transmitter)
              ))

      (set! (.-onclose socket)
            (fn [e]
              ;; not a big fan of reconnecting automatically since a disconnect
              ;; may signal a change of config, safer to just reload the page
              (.warn js/console "DEVTOOLS: disconnected!")
              (reset! *socket* nil)
              ))

      (set! (.-onerror socket)
            (fn [e]))

      )))

(when ^boolean devtools/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @*socket*]
    (js/console.log "DEVTOOLS: connection reset!")
    (set! (.-onclose s) (fn [e]))
    (.close s))
  (repl-connect))
