(ns shadow.cljs.devtools.client.browser
  (:require [cljs.reader :as reader]
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

(defonce socket-ref (volatile! nil))

(defn devtools-msg [msg & args]
  (.apply (.-log js/console) nil (into-array (into [(str "%cDEVTOOLS: " msg) "color: blue;"] args))))

(defn ws-msg [msg]
  (if-let [s @socket-ref]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(defonce scripts-to-load (atom []))

(def loaded? js/goog.isProvided_)

(defn goog-is-loaded? [name]
  (gobj/get js/goog.dependencies_.written name))

(def goog-base-rc
  [:shadow.build.classpath/resource "goog/base.js"])

(defn src-is-loaded? [{:keys [resource-id output-name] :as src}]
  ;; FIXME: don't like this special case handling, but goog/base.js will always be loaded
  ;; but not as a separate file
  (or (= goog-base-rc resource-id)
      (goog-is-loaded? output-name)))

(defn module-is-active? [module]
  (contains? @active-modules-ref module))

(defn script-eval
  "js/eval doesn't get optimized properly, this hack seems to do the trick"
  [code]
  (let [node (js/document.createElement "script")]
    (.appendChild node (js/document.createTextNode code))
    (js/document.body.appendChild node)
    (js/document.body.removeChild node)))

(defn do-js-load [sources]
  (doseq [{:keys [resource-id resource-name js] :as src} sources]
    (devtools-msg "load JS" resource-name)
    (script-eval (str js "\n//# sourceURL=" resource-name))))

(defn do-js-reload
  "stops the running app, loads sources, starts app
   stop might be async as several node APIs are async
   start is sync since we don't need to do anything after startup finishes"
  [sources]
  (let [[stop-fn stop-label]
        (cond
          (seq env/before-load)
          (let [stop-fn (js/goog.getObjectByName env/before-load js/$CLJS)]
            [(fn [done]
               (stop-fn)
               (done))
             env/before-load])

          (seq env/before-load-async)
          [(js/goog.getObjectByName env/before-load-async js/$CLJS)
           env/before-load-async]

          :else
          [(fn [done] (done))
           nil])]

    (when stop-label
      (devtools-msg "app shutdown" stop-label))
    (stop-fn
      (fn [state]
        (do-js-load sources)
        (when (seq env/after-load)
          (devtools-msg "app start" env/after-load))

        ;; must delay loading start-fn until here, otherwise we load the old version
        (when-let [start-fn
                   (when (seq env/after-load)
                     (js/goog.getObjectByName env/after-load js/$CLJS))]
          (start-fn state))
        ))))

(defn load-sources [sources callback]
  (if (empty? sources)
    (callback [])
    (xhr/send
      (env/files-url)
      (fn [res]
        (this-as ^goog req
          (let [content
                (-> req
                    (.getResponseText)
                    (reader/read-string))]
            (callback content)
            )))
      "POST"
      (pr-str {:client :browser
               :sources (into [] (map :resource-id) sources)})
      #js {"content-type" "application/edn; charset=utf-8"})))

(defn handle-build-complete [{:keys [info] :as msg}]
  (let [{:keys [sources compiled]}
        info

        warnings
        (->> (for [{:keys [resource-name warnings] :as src} sources
                   :when (not (:from-jar src))
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (distinct)
             (into []))]

    (doseq [{:keys [msg line column resource-name] :as w} warnings]
      (js/console.warn (str "BUILD-WARNING in " resource-name " at [" line ":" column "]\n\t" msg)))

    (when env/autoload
      ;; load all files for current build:
      ;; of modules that are active
      ;; and are either not loaded yet
      ;; or specifically marked for reload
      (let [sources-to-get
            (->> sources
                 (filter
                   (fn [{:keys [module] :as rc}]
                     (or (= "js" env/module-format)
                         (module-is-active? module))))
                 (filter
                   (fn [{:keys [output-name resource-id] :as src}]
                     (or (not (src-is-loaded? src))
                         (and (contains? compiled resource-id)
                              ;; never reload files from jar
                              ;; they can't be hot-swapped so the only way they get re-compiled
                              ;; is if they have warnings, which we can't to anything about
                              (not (:from-jar src))))))
                 (into []))]


        ;; FIXME: should allow reload with warnings
        (when (and (empty? warnings)
                   (seq sources-to-get))
          (load-sources sources-to-get do-js-reload)
          )))))

(defn handle-asset-watch [{:keys [updates] :as msg}]
  (doseq [path updates
          ;; FIXME: could support images?
          :when (str/ends-with? path "css")]
    (when-let [node (js/document.querySelector (str "link[href^=\"" path "\"]"))]
      (let [new-link
            (doto (js/document.createElement "link")
              (.setAttribute "rel" "stylesheet")
              (.setAttribute "href" (str path "?r=" (rand))))]

        (devtools-msg "load CSS" path)
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
  (let [result (env/repl-call #(js/eval js) repl-error)]
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

(defn repl-init [{:keys [repl-state id]}]
  (load-sources
    ;; maybe need to load some missing files to init REPL
    (->> (:repl-sources repl-state)
         (remove src-is-loaded?)
         (into []))
    (fn [sources]
      (do-js-load sources)
      (ws-msg {:type :repl/init-complete :id id})
      (devtools-msg "REPL init successful"))))

(defn repl-set-ns [{:keys [id ns]}]
  ;; (js/console.log "repl/set-ns" (str ns))
  (ws-msg {:type :repl/set-ns-complete :id id :ns ns}))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg}]
  ;; (js/console.log "ws-msg" msg)
  (case type
    :asset-watch
    (handle-asset-watch msg)

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
        (env/ws-url js/document.location.hostname :browser)

        socket
        (js/WebSocket. ws-url)]

    (vreset! socket-ref socket)

    (set! (.-onmessage socket)
      (fn [e]
        #_(set-print-fn! (fn [& args]
                           (ws-msg {:type :repl/out
                                    :out (into [] args)})
                           (apply print-fn args)))

        (env/process-ws-msg e handle-message)
        ))

    (set! (.-onopen socket)
      (fn [e]
        ;; :module-format :js already patches provide
        (when (= "goog" env/module-format)
          ;; patch away the already declared exception
          (set! (.-provide js/goog) js/goog.constructNamespace_))
        (devtools-msg "connected!")
        ))

    (set! (.-onclose socket)
      (fn [e]
        ;; not a big fan of reconnecting automatically since a disconnect
        ;; may signal a change of config, safer to just reload the page
        (devtools-msg "disconnected!")
        (vreset! socket-ref nil)
        ))

    (set! (.-onerror socket)
      (fn [e]))
    ))

(when ^boolean env/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @socket-ref]
    (devtools-msg "connection reset!")
    (set! (.-onclose s) (fn [e]))
    (.close s)
    (vreset! socket-ref nil))
  (ws-connect))
