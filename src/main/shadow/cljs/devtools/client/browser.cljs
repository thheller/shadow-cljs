(ns shadow.cljs.devtools.client.browser
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.dom :as gdom]
    [goog.userAgent.product :as product]
    [goog.Uri]
    [goog.net.XhrIo :as xhr]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.console]
    [shadow.cljs.devtools.client.hud :as hud]
    ))

(defonce active-modules-ref
  (volatile! #{}))

(defonce repl-ns-ref (atom nil))

(defn module-loaded [name]
  (vswap! active-modules-ref conj (keyword name)))

(defonce socket-ref (volatile! nil))

(defn devtools-msg [msg & args]
  (.apply (.-log js/console) nil (into-array (into [(str "%cshadow-cljs: " msg) "color: blue;"] args))))

(defn ws-msg [msg]
  (if-let [s @socket-ref]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(defonce scripts-to-load (atom []))

(def loaded? js/goog.isProvided_)

(defn goog-is-loaded? [name]
  (js/SHADOW_ENV.isLoaded name))

(def goog-base-rc
  [:shadow.build.classpath/resource "goog/base.js"])

(defn src-is-loaded? [{:keys [resource-id output-name] :as src}]
  ;; FIXME: don't like this special case handling, but goog/base.js will always be loaded
  ;; but not as a separate file
  (or (= goog-base-rc resource-id)
      (goog-is-loaded? output-name)))

(defn module-is-active? [module]
  (contains? @active-modules-ref module))

(defn script-eval [code]
  (js/goog.globalEval code))

(defn do-js-load [sources]
  (doseq [{:keys [resource-id output-name resource-name js] :as src} sources]
    ;; should really stop using this and rather maintain our own record
    ;; but without this hot-reload will reload shadow-js files with each cycle
    ;; since they don't set it
    (js/SHADOW_ENV.setLoaded output-name)

    (devtools-msg "load JS" resource-name)
    (env/before-load-src src)
    (script-eval (str js "\n//# sourceURL=" resource-name))))

(defn do-js-reload [msg sources complete-fn failure-fn]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      (fn [fn-sym]
        (devtools-msg (str "can't find fn " fn-sym)))
      :log-call-async
      (fn [fn-sym]
        (devtools-msg (str "call async " fn-sym)))
      :log-call
      (fn [fn-sym]
        (devtools-msg (str "call " fn-sym))))
    #(do-js-load sources)
    complete-fn
    failure-fn))

(defn do-js-requires
  "when (require '[\"some-str\" :as x]) is done at the REPL we need to manually call the shadow.js.require for it
   since the file only adds the shadow$provide. only need to do this for shadow-js."
  [js-requires]
  (doseq [js-ns js-requires]
    (let [require-str (str "var " js-ns " = shadow.js.require(\"" js-ns "\");")]
      (script-eval require-str))))

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

(defn handle-build-complete [{:keys [info reload-info] :as msg}]
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

    (if-not env/autoload
      (hud/load-end-success)
      ;; load all files for current build:
      ;; of modules that are active
      ;; and are either not loaded yet
      ;; or specifically marked for reload
      (when (or (empty? warnings) env/ignore-warnings)
        (let [sources-to-get
              (->> sources
                   (filter
                     (fn [{:keys [module] :as rc}]
                       (or (= "js" env/module-format)
                           (module-is-active? module))))
                   ;; don't reload namespaces that have ^:dev/never-reload meta
                   (remove (fn [{:keys [ns]}]
                             (contains? (:never-load reload-info) ns)))
                   (filter
                     (fn [{:keys [ns resource-id] :as src}]
                       (or (contains? (:always-load reload-info) ns)
                           (not (src-is-loaded? src))
                           (and (contains? compiled resource-id)
                                ;; never reload files from jar
                                ;; they can't be hot-swapped so the only way they get re-compiled
                                ;; is if they have warnings, which we can't to anything about
                                (not (:from-jar src))))))
                   (into []))]

          (if-not (seq sources-to-get)
            (hud/load-end-success)
            (load-sources sources-to-get #(do-js-reload msg % hud/load-end-success hud/load-failure))
            ))))))

;; capture this once because the path may change via pushState
(def ^goog page-load-uri
  (when js/goog.global.document
    (goog.Uri/parse js/document.location.href)))

(defn handle-asset-watch [{:keys [updates] :as msg}]
  (doseq [path updates
          ;; FIXME: could support images?
          :when (str/ends-with? path "css")]
    (doseq [node (array-seq (js/document.querySelectorAll "link[rel=\"stylesheet\"]"))
            :let [^goog node-uri (goog.Uri/parse (.getAttribute node "href"))
                  node-uri-resolved (.resolve page-load-uri node-uri)
                  node-abs (.getPath ^goog node-uri-resolved)]
            :when (and (or (= (.hasSameDomainAs page-load-uri node-uri))
                           (not (.hasDomain node-uri)))
                       (= node-abs path))]

      (let [new-link
            (doto (.cloneNode node true)
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

(defn repl-error [e]
  (js/console.error "repl/invoke error" e)
  (-> (env/repl-error e)
      (assoc :ua-product (get-ua-product)
             :asset-root (get-asset-root))))

(defn repl-invoke [{:keys [id js]}]
  (let [result (env/repl-call #(js/eval js) repl-error)]
    (-> result
        (assoc :id id)
        (ws-msg))))

(defn repl-require [{:keys [id sources reload-namespaces js-requires] :as msg}]
  (let [sources-to-load
        (->> sources
             (remove (fn [{:keys [provides] :as src}]
                       (and (src-is-loaded? src)
                            (not (some reload-namespaces provides)))))
             (into []))]

    (load-sources
      sources-to-load
      (fn [sources]
        (do-js-load sources)
        (when (seq js-requires)
          (do-js-requires js-requires))
        (ws-msg {:type :repl/require-complete :id id})
        ))))

(defn repl-init [{:keys [repl-state id]}]
  (reset! repl-ns-ref (get-in repl-state [:current :ns]))
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
  (reset! repl-ns-ref ns)
  (ws-msg {:type :repl/set-ns-complete :id id :ns ns}))

(def close-reason-ref (volatile! nil))

;; FIXME: core.async-ify this
(defn handle-message [{:keys [type] :as msg}]
  ;; (js/console.log "ws-msg" msg)
  (hud/connection-error-clear!)
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
    (do (hud/hud-warnings msg)
        (handle-build-complete msg))

    :build-failure
    (do (hud/load-end)
        (hud/hud-error msg))

    :build-init
    (hud/hud-warnings msg)

    :build-start
    (do (hud/hud-hide)
        (hud/load-start))

    :pong
    nil

    :client/stale
    (vreset! close-reason-ref "Stale Client! You are not using the latest compilation output!")

    :client/no-worker
    (vreset! close-reason-ref (str "watch for build \"" env/build-id "\" not running"))

    ;; default
    :ignored))

(defn compile [text callback]
  (xhr/send
    (str "http" (when env/ssl "s") "://" env/server-host ":" env/server-port "/worker/compile/" env/build-id "/" env/proc-id "/browser")
    (fn [res]
      (this-as ^goog req
        (let [actions
              (-> req
                  (.getResponseText)
                  (reader/read-string))]
          (when callback
            (callback actions)))))
    "POST"
    (pr-str {:input text})
    #js {"content-type" "application/edn; charset=utf-8"}))

(defn heartbeat! []
  (when-let [s @socket-ref]
    (.send s (pr-str {:type :ping :v (js/Date.now)}))
    (js/setTimeout heartbeat! 30000)))


(defn ws-connect []
  (let [print-fn
        cljs.core/*print-fn*

        ws-url
        (env/ws-url :browser)

        socket
        (js/WebSocket. ws-url)]

    (vreset! socket-ref socket)

    (set! (.-onmessage socket)
      (fn [e]
        (env/process-ws-msg (. e -data) handle-message)
        ))

    (set! (.-onopen socket)
      (fn [e]
        (hud/connection-error-clear!)
        (vreset! close-reason-ref nil)
        ;; :module-format :js already patches provide
        (when (= "goog" env/module-format)
          ;; patch away the already declared exception
          (set! (.-provide js/goog) js/goog.constructNamespace_))

        (env/set-print-fns! ws-msg)

        (devtools-msg "WebSocket connected!")
        ))

    (set! (.-onclose socket)
      (fn [e]
        ;; not a big fan of reconnecting automatically since a disconnect
        ;; may signal a change of config, safer to just reload the page
        (devtools-msg "WebSocket disconnected!")
        (hud/connection-error (or @close-reason-ref "Connection closed!"))
        (vreset! socket-ref nil)
        (env/reset-print-fns!)
        ))

    (set! (.-onerror socket)
      (fn [e]
        (hud/connection-error "Connection failed!")
        (devtools-msg "websocket error" e)))

    (js/setTimeout heartbeat! 30000)
    ))

(when ^boolean env/enabled
  ;; disconnect an already connected socket, happens if this file is reloaded
  ;; pretty much only for me while working on this file
  (when-let [s @socket-ref]
    (devtools-msg "connection reset!")
    (set! (.-onclose s) (fn [e]))
    (.close s)
    (vreset! socket-ref nil))

  ;; for /browser-repl in case the page is reloaded
  ;; otherwise the browser seems to still have the websocket open
  ;; when doing the reload
  (js/window.addEventListener "beforeunload"
    (fn []
      (when-let [s @socket-ref]
        (.close s))))

  (ws-connect))
