(ns shadow.cljs.devtools.client.browser
  (:require-macros [cljs.core.async.macros :refer (go alt!)])
  (:require [cljs.reader :as reader]
            [cljs.core.async :as async]
            [clojure.string :as str]
            [goog.dom :as gdom]
            [goog.object :as gobj]
            [goog.net.jsloader :as loader]
            [goog.userAgent.product :as product]
            [goog.Uri]
            [goog.net.XhrIo :as xhr]
            [shadow.cljs.devtools.client.env :as env]
            [shadow.cljs.devtools.client.console]
            ))

(defonce active-modules-ref
  (volatile! #{}))

(defn module-loaded [name]
  (vswap! active-modules-ref conj (keyword name)))

(defonce socket-ref (atom nil))

(defn devtools-msg [msg & args]
  (.apply (.-log js/console) nil (into-array (into [(str "%c" msg) "color: blue;"] args))))

(defn ws-msg [msg]
  (if-let [s @socket-ref]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(defonce scripts-to-load (atom []))

(def loaded? js/goog.isProvided_)

(defn goog-is-loaded? [name]
  (gobj/get js/goog.dependencies_.written name))

(defn src-is-loaded? [{:keys [js-name] :as src}]
  (goog-is-loaded? js-name))

(defn module-is-active? [module]
  (contains? @active-modules-ref module))

(defn do-js-load [sources]
  (doseq [{:keys [name js] :as src} sources]
    (devtools-msg "LOAD:" name)
    (js/eval (str js "\n// @sourceURL=" name))))

(defn do-js-reload [sources]
  (let [reload-state
        (when env/before-load
          (let [fn (js/goog.getObjectByName env/before-load js/$CLJS)]
            (devtools-msg "Executing :before-load" env/before-load)
            (fn)))]

    (do-js-load sources)

    (when env/after-load
      (let [fn (js/goog.getObjectByName env/after-load js/$CLJS)]
        (devtools-msg "Executing :after-load " env/after-load)
        (if-not env/reload-with-state
          (fn)
          (fn reload-state)))
      )))

(defn load-sources [sources callback]
  (if (empty? sources)
    (callback [])
    (xhr/send
      (str "http://" env/repl-host ":" env/repl-port "/files")
      (fn [res]
        (this-as req
          (let [content
                (-> req
                    (.getResponseText)
                    (reader/read-string))]
            (callback content)
            )))
      "POST"
      (pr-str {:client :browser
               :sources (into [] (map :name) sources)})
      #js {"content-type" "application/edn; charset=utf-8"})))

(defn handle-build-complete [{:keys [info] :as msg}]
  (let [{:keys [sources compiled]}
        info

        warnings
        (->> (for [{:keys [name warnings] :as src} sources
                   warning warnings]
               (assoc warning :source-name name))
             (distinct)
             (into []))]

    (doseq [{:keys [msg line column source-name] :as w} warnings]
      (js/console.warn (str "BUILD-WARNING in " source-name " at [" line ":" column "]\n\t" msg)))

    (when env/autoload
      ;; load all files for current build:
      ;; of modules that are active
      ;; and are either not loaded yet
      ;; or specifically marked for reload
      (let [sources-to-get
            (->> sources
                 (filter
                   (fn [{:keys [module]}]
                     (or (= "js" env/module-format)
                         (module-is-active? module))))
                 (filter
                   (fn [{:keys [js-name name]}]
                     (or (not (goog-is-loaded? js-name))
                         (contains? compiled name))))
                 (into []))]

        ;; FIXME: should allow reload with warnings
        (when (empty? warnings)
          (load-sources sources-to-get do-js-reload)
          )))))

(defn handle-css-changes [{:keys [asset-path name manifest] :as pkg}]
  (doseq [[css-name css-file-name] manifest]
    (when-let [node (js/document.querySelector (str "link[data-css-package=\"" name "\"][data-css-module=\"" css-name "\"]"))]
      (let [full-path
            (str asset-path "/" css-file-name)

            new-link
            (doto (js/document.createElement "link")
              (.setAttribute "rel" "stylesheet")
              (.setAttribute "href" (str full-path "?r=" (rand)))
              (.setAttribute "data-css-package" name)
              (.setAttribute "data-css-module" css-name))]

        (devtools-msg "LOAD CSS:" full-path)
        (gdom/insertSiblingAfter new-link node)
        (gdom/removeNode node)
        ))))

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

(defn repl-error [result e]
  (js/console.error "repl/invoke error" e)
  (assoc result
    :ua-product (get-ua-product)
    :error (str e)
    :asset-root (get-asset-root)
    :stacktrace (if (.hasOwnProperty e "stack")
                  (.-stack e)
                  "No stacktrace available.")))

(defn repl-invoke [{:keys [id js]}]
  (let [result (env/repl-call #(js/eval js) pr-str repl-error)]
    (-> result
        (assoc :id id)
        (ws-msg))))

(defn repl-require [{:keys [id sources reload] :as msg}]
  (let [sources-to-load
        (cond
          (= :reload reload)
          (let [all (butlast sources)
                self (last sources)]
            (-> (into [] (remove src-is-loaded?) all)
                (conj self)))

          (= :reload-all reload)
          sources

          :else
          (remove src-is-loaded? sources))]

    (load-sources
      sources-to-load
      (fn [sources]
        (do-js-load sources)
        (ws-msg {:type :repl/require-complete :id id})
        ))))

(defn repl-init [{:keys [repl-state]}]
  (load-sources
    ;; maybe need to load some missing files to init REPL
    (->> (:repl-sources repl-state)
         (remove src-is-loaded?)
         (into []))
    (fn [sources]
      (do-js-load sources)
      (ws-msg {:type :repl/init-complete})
      (devtools-msg "DEVTOOLS: repl init successful"))))

(defn repl-set-ns [{:keys [ns]}]
  ;; (js/console.log "repl/set-ns" (str ns))
  (ws-msg {:type :repl/set-ns-complete}))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg}]
  ;; (js/console.log "ws-msg" msg)
  (case type
    ;; FIXME: doesn't work anymore
    :css/reload
    (handle-css-changes msg)

    :repl/invoke
    (repl-invoke msg)

    :repl/require
    (repl-require msg)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/init
    (repl-init msg)

    :build-complete
    (handle-build-complete msg)

    ;; default
    :ignored))

(defn ws-connect []
  (let [print-fn
        cljs.core/*print-fn*

        ws-url
        (env/ws-url :browser)

        socket
        (js/WebSocket. ws-url)]

    (reset! socket-ref socket)

    (set! (.-onmessage socket)
      (fn [e]
        #_(set-print-fn! (fn [& args]
                           (ws-msg {:type :repl/out
                                    :out (into [] args)})
                           (apply print-fn args)))

        (let [text
              (.-data e)

              msg
              (try
                (reader/read-string text)
                (catch :default e
                  (js/console.warn "failed to parse msg" e text)
                  nil))]
          (when msg
            (handle-message msg)))
        ))

    (set! (.-onopen socket)
      (fn [e]
        ;; :module-format :js already patches provide
        (when (= "goog" env/module-format)
          ;; patch away the already declared exception
          (set! (.-provide js/goog) js/goog.constructNamespace_))
        (devtools-msg "DEVTOOLS: connected!")
        ))

    (set! (.-onclose socket)
      (fn [e]
        ;; not a big fan of reconnecting automatically since a disconnect
        ;; may signal a change of config, safer to just reload the page
        (devtools-msg "DEVTOOLS: disconnected!")
        (reset! socket-ref nil)
        ))

    (set! (.-onerror socket)
      (fn [e]))
    ))

(when ^boolean env/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @socket-ref]
    (devtools-msg "DEVTOOLS: connection reset!")
    (set! (.-onclose s) (fn [e]))
    (.close s))
  (ws-connect))
