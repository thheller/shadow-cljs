(ns shadow.cljs.devtools.client.worker
  (:require
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.net.XhrIo :as xhr]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.console]
    ))

(defonce socket-ref (volatile! nil))

(defn devtools-msg [msg & args]
  (if (seq env/log-style)
    (js/console.log.apply js/console (into-array (into [(str "%c\uD83E\uDC36 [WORKER] shadow-cljs: " msg) env/log-style] args)))
    (js/console.log.apply js/console (into-array (into [(str "shadow-cljs: " msg)] args)))))

(defn ws-msg [msg]
  (if-let [s @socket-ref]
    (.send s (pr-str msg))
    (js/console.warn "WEBSOCKET NOT CONNECTED" (pr-str msg))))

(defonce scripts-to-load (atom []))

(def loaded? js/goog.isProvided_)

(defn goog-is-loaded? [name]
  (js/$CLJS.SHADOW_ENV.isLoaded name))

(def goog-base-rc
  [:shadow.build.classpath/resource "goog/base.js"])

(defn src-is-loaded? [{:keys [resource-id output-name] :as src}]
  ;; FIXME: don't like this special case handling, but goog/base.js will always be loaded
  ;; but not as a separate file
  (or (= goog-base-rc resource-id)
      (goog-is-loaded? output-name)))

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
      (script-eval (str js "\n//# sourceURL=" resource-name))
      (catch :default e
        (js/console.error (str "Failed to load " resource-name) e)
        (throw (js/Error. (str "Failed to load " resource-name ": " (.-message e))))))))

(defn do-js-reload [msg sources complete-fn failure-fn]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      (fn [fn-sym]
        ;; FIXME: need a better system for this
        ;; this will attempt to call registered callback fns from the main modules
        ;; but that code isn't loaded so it fails. it shouldn't even try.
        ;; (devtools-msg (str "can't find fn " fn-sym))
        )
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

    (when env/autoload
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
                           (env/module-is-active? module))))
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

          (when (seq sources-to-get)
            (when-not (seq (get-in msg [:reload-info :after-load]))
              (devtools-msg "reloading code but no :after-load hooks are configured!"
                "https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks"))
            (load-sources sources-to-get #(do-js-reload msg % (fn []) (fn [])))
            ))))))

(defn repl-error [e]
  (js/console.error "repl/invoke error" e)
  (env/repl-error e))

(defn global-eval [js]
  (js* "(0,eval)(~{});" js))

(defn repl-invoke [{:keys [id js]}]
  (let [result (env/repl-call #(global-eval js) repl-error)]
    (-> result
        (assoc :id id)
        (ws-msg))))

(defn repl-require [{:keys [id sources reload-namespaces js-requires] :as msg} done]
  (let [sources-to-load
        (->> sources
             (remove (fn [{:keys [provides] :as src}]
                       (and (src-is-loaded? src)
                            (not (some reload-namespaces provides)))))
             (into []))]

    (load-sources
      sources-to-load
      (fn [sources]
        (try
          (do-js-load sources)
          (when (seq js-requires)
            (do-js-requires js-requires))
          (ws-msg {:type :repl/require-complete :id id})
          (catch :default e
            (ws-msg {:type :repl/require-error :id id :error (.-message e)}))
          (finally
            (done)))))))

(defn repl-init [{:keys [repl-state id]} done]
  (load-sources
    ;; maybe need to load some missing files to init REPL
    (->> (:repl-sources repl-state)
         (remove src-is-loaded?)
         (into []))
    (fn [sources]
      (do-js-load sources)
      (ws-msg {:type :repl/init-complete :id id})
      (devtools-msg "REPL session start successful")
      (done))))

(defn repl-set-ns [{:keys [id ns]}]
  (ws-msg {:type :repl/set-ns-complete :id id :ns ns}))

(def close-reason-ref (volatile! nil))

(defn handle-message [{:keys [type] :as msg} done]
  ;; (js/console.log "worker-ws-msg" msg)
  (case type
    :asset-watch
    :no-op

    :repl/invoke
    (repl-invoke msg)

    :repl/require
    (repl-require msg done)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/init
    (repl-init msg done)

    :repl/session-start
    (repl-init msg done)

    :repl/ping
    (ws-msg {:type :repl/pong :time-server (:time-server msg) :time-runtime (js/Date.now)})

    :build-complete
    (handle-build-complete msg)

    :build-failure
    :no-op

    :build-init
    :no-op

    :build-start
    :no-op

    :pong
    nil

    :client/stale
    (js/console.warn "Stale Client! You are not using the latest compilation output!")

    :client/no-worker
    (js/console.warn (str "watch for build \"" env/build-id "\" not running"))

    :custom-msg
    (env/publish! (:payload msg))

    ;; default
    :ignored)

  (when-not (contains? env/async-ops type)
    (done)))

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

(defn ws-connect []
  (try
    (let [print-fn
          cljs.core/*print-fn*

          ws-url
          (env/ws-url :worker)

          socket
          (js/WebSocket. ws-url)]

      (vreset! socket-ref socket)

      (set! (.-onmessage socket)
        (fn [e]
          (env/process-ws-msg (. e -data) handle-message)
          ))

      (set! (.-onopen socket)
        (fn [e]
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
          (vreset! socket-ref nil)
          (env/reset-print-fns!)
          ))

      (set! (.-onerror socket)
        (fn [e]
          (devtools-msg "websocket error" e))))
    (catch :default e
      (devtools-msg "WebSocket setup failed" e))))

(when ^boolean env/enabled
  (ws-connect))
