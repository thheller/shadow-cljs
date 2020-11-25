(ns shadow.cljs.devtools.client.browser
  (:require
    [clojure.string :as str]
    [goog.dom :as gdom]
    [goog.userAgent :as ua]
    [goog.userAgent.product :as uap]
    [goog.Uri]
    [shadow.json :as j]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.console]
    [shadow.cljs.devtools.client.hud :as hud]
    [shadow.cljs.devtools.client.websocket :as ws]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]))

(defn devtools-msg [msg & args]
  (when env/log
    (if (seq env/log-style)
      (js/console.log.apply js/console (into-array (into [(str "%cshadow-cljs: " msg) env/log-style] args)))
      (js/console.log.apply js/console (into-array (into [(str "shadow-cljs: " msg)] args))))))

(defn script-eval [code]
  (js/goog.globalEval code))

(defn do-js-load [sources]
  (doseq [{:keys [resource-id output-name resource-name js] :as src} sources]
    ;; should really stop using this and rather maintain our own record
    ;; but without this hot-reload will reload shadow-js files with each cycle
    ;; since they don't set it
    (js/$CLJS.SHADOW_ENV.setLoaded output-name)

    (devtools-msg "load JS" resource-name)
    (env/before-load-src src)
    (try
      (script-eval (str js "\n//# sourceURL=" js/$CLJS.SHADOW_ENV.scriptBase output-name))
      (catch :default e
        (when env/log
          (js/console.error (str "Failed to load " resource-name) e))
        (throw (js/Error. (str "Failed to load " resource-name ": " (.-message e))))))))

(defn do-js-reload [msg sources complete-fn failure-fn]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      ;; FIXME: this gets noisy when using web-workers and either main or the workers not having certain code loaded
      ;; should properly filter hook-fns and only attempt to call those that actually apply
      ;; but thats a bit of work since we don't currently track the namespaces that are loaded.
      (fn [fn-sym]
        #_(devtools-msg (str "can't find fn " fn-sym)))
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

(defn handle-build-complete [runtime {:keys [info reload-info] :as msg}]
  (let [warnings
        (->> (for [{:keys [resource-name warnings] :as src} (:sources info)
                   :when (not (:from-jar src))
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (distinct)
             (into []))]

    (when env/log
      (doseq [{:keys [msg line column resource-name] :as w} warnings]
        (js/console.warn (str "BUILD-WARNING in " resource-name " at [" line ":" column "]\n\t" msg))))

    (if-not env/autoload
      (hud/load-end-success)
      (when (or (empty? warnings) env/ignore-warnings)
        (let [sources-to-get
              (env/filter-reload-sources info reload-info)]

          (if-not (seq sources-to-get)
            (hud/load-end-success)
            (do (when-not (seq (get-in msg [:reload-info :after-load]))
                  (devtools-msg "reloading code but no :after-load hooks are configured!"
                    "https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks"))
                (cljs-shared/load-sources runtime sources-to-get #(do-js-reload msg % hud/load-end-success hud/load-failure)))
            ))))))

;; capture this once because the path may change via pushState
(def ^goog page-load-uri
  (when js/goog.global.document
    (goog.Uri/parse js/document.location.href)))

(defn match-paths [old new]
  (if (= "file" (.getScheme page-load-uri))
    ;; new is always an absolute path, strip first /
    ;; FIXME: assuming that old is always relative
    (let [rel-new (subs new 1)]
      (when (or (= old rel-new)
                (str/starts-with? old (str rel-new "?")))
        rel-new))
    ;; special handling for browsers including relative css
    (let [^goog node-uri (goog.Uri/parse old)
          node-uri-resolved (.resolve page-load-uri node-uri)
          node-abs (.getPath ^goog node-uri-resolved)]

      (and (or (= (.hasSameDomainAs page-load-uri node-uri))
               (not (.hasDomain node-uri)))
           (= node-abs new)
           new))))

(defn handle-asset-update [{:keys [updates] :as msg}]
  (doseq [path updates
          ;; FIXME: could support images?
          :when (str/ends-with? path "css")]
    (doseq [^js node (array-seq (js/document.querySelectorAll "link[rel=\"stylesheet\"]"))
            :when (not (.-shadow$old node))
            :let [path-match (match-paths (.getAttribute node "href") path)]
            :when path-match]

      (let [new-link
            (doto (.cloneNode node true)
              (.setAttribute "href" (str path-match "?r=" (rand))))]

        ;; safeguard to prevent duplicating nodes in case a second css update happens
        ;; before the first onload triggers.
        (set! node -shadow$old true)

        (set! (.-onload new-link)
          (fn [e]
            (gdom/removeNode node)))

        (devtools-msg "load CSS" path-match)
        (gdom/insertSiblingAfter new-link node)
        ))))

(defn global-eval [js]
  (if (not= "undefined" (js* "typeof(module)"))
    ;; don't eval in the global scope in case of :npm-module builds running in webpack
    (js/eval js)
    ;; hack to force eval in global scope
    ;; goog.globalEval doesn't have a return value so can't use that for REPL invokes
    (js* "(0,eval)(~{});" js)))

(defn repl-init [runtime {:keys [repl-state]}]
  (cljs-shared/load-sources
    runtime
    ;; maybe need to load some missing files to init REPL
    (->> (:repl-sources repl-state)
         (remove env/src-is-loaded?)
         (into []))
    (fn [sources]
      (do-js-load sources)
      (devtools-msg "ready!"))))

(def runtime-info
  (when (exists? js/SHADOW_CONFIG)
    (j/to-clj js/SHADOW_CONFIG)))

(def client-info
  (merge
    runtime-info
    {:host (if js/goog.global.document
             :browser
             :browser-worker)
     :user-agent
     (str
       (cond
         ua/OPERA
         "Opera"
         uap/CHROME
         "Chrome"
         ua/IE
         "MSIE"
         ua/EDGE
         "Edge"
         ua/GECKO
         "Firefox"
         ua/SAFARI
         "Safari"
         ua/WEBKIT
         "Webkit")
       " " ua/VERSION
       " [" ua/PLATFORM "]")

     :dom (some? js/goog.global.document)}))

(defonce ws-was-welcome-ref (atom false))

(when (and env/enabled (pos? env/worker-client-id))

  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code]
      (global-eval code))

    cljs-shared/IHostSpecific
    (do-invoke [this {:keys [js] :as _}]
      (global-eval js))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (cljs-shared/load-sources
        runtime
        ;; maybe need to load some missing files to init REPL
        (->> repl-sources
             (remove env/src-is-loaded?)
             (into []))
        (fn [sources]
          (do-js-load sources)
          (done))))

    (do-repl-require [runtime {:keys [sources reload-namespaces js-requires] :as msg} done error]
      (let [sources-to-load
            (->> sources
                 (remove (fn [{:keys [provides] :as src}]
                           (and (env/src-is-loaded? src)
                                (not (some reload-namespaces provides)))))
                 (into []))]

        (if-not (seq sources-to-load)
          (done [])
          (shared/call runtime
            {:op :cljs-load-sources
             :to env/worker-client-id
             :sources (into [] (map :resource-id) sources-to-load)}

            {:cljs-sources
             (fn [{:keys [sources] :as msg}]
               (try
                 (do-js-load sources)
                 (when (seq js-requires)
                   (do-js-requires js-requires))
                 (done sources-to-load)
                 (catch :default ex
                   (error ex))))})))))

  (cljs-shared/add-plugin! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]

        (api/add-extension runtime ::client
          {:on-welcome
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (reset! ws-was-welcome-ref true)
             (hud/connection-error-clear!)
             (env/patch-goog!)
             (devtools-msg (str "#" (-> runtime :state-ref deref :client-id) " ready!")))

           :on-disconnect
           (fn [e]
             ;; don't show error if connection was denied
             ;; that already shows an error
             (when @ws-was-welcome-ref
               (hud/connection-error "The Websocket connection was closed!")

               (reset! ws-was-welcome-ref false)
               ))

           :on-reconnect
           (fn [e]
             (hud/connection-error "Reconnecting ..."))

           :ops
           {:access-denied
            (fn [msg]
              (reset! ws-was-welcome-ref false)
              (hud/connection-error
                (str "Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."
                     " Is the watch for this build running?")))

            :cljs-runtime-init
            (fn [msg]
              (repl-init runtime msg))

            :cljs-asset-update
            (fn [{:keys [updates] :as msg}]
              ;; (js/console.log "cljs-asset-update" msg)
              (handle-asset-update msg))

            :cljs-build-configure
            (fn [msg])

            :cljs-build-start
            (fn [msg]
              ;; (js/console.log "cljs-build-start" msg)
              (hud/hud-hide)
              (hud/load-start)
              (env/run-custom-notify! (assoc msg :type :build-start)))

            :cljs-build-complete
            (fn [msg]
              ;; (js/console.log "cljs-build-complete" msg)
              (let [msg (env/add-warnings-to-info msg)]
                (hud/hud-warnings msg)
                (handle-build-complete runtime msg)
                (env/run-custom-notify! (assoc msg :type :build-complete))))

            :cljs-build-failure
            (fn [msg]
              ;; (js/console.log "cljs-build-failure" msg)
              (hud/load-end)
              (hud/hud-error msg)
              (env/run-custom-notify! (assoc msg :type :build-failure)))

            ::env/worker-notify
            (fn [{:keys [event-op client-id]}]
              (cond
                (and (= :client-disconnect event-op)
                     (= client-id env/worker-client-id))
                (do (hud/connection-error-clear!)
                    (hud/connection-error "The watch for this build was stopped!"))

                (= :client-connect event-op)
                (do (hud/connection-error-clear!)
                    (hud/connection-error "The watch for this build was restarted. Reload required!"))
                ))}})
        svc))

    (fn [{:keys [runtime] :as svc}]
      (api/del-extension runtime ::client)))

  (cljs-shared/init-runtime! client-info ws/start ws/send ws/stop))