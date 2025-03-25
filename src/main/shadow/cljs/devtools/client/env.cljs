(ns shadow.cljs.devtools.client.env
  (:require
    [goog.object :as gobj]
    [clojure.string :as str]
    [clojure.set :as set]))

(defonce active-modules-ref
  (volatile! #{}))

(defn module-loaded [name]
  (vswap! active-modules-ref conj (keyword name)))

(defn module-is-active? [module]
  (contains? @active-modules-ref module))

;; FIXME: make this persistent somehow?
(defonce runtime-id (str (random-uuid)))

(goog-define enabled false)

(goog-define log true)

(goog-define autoload true)

(goog-define module-format "goog")

(goog-define reload-with-state false)

(goog-define build-id "")

(goog-define proc-id "")

(goog-define worker-client-id 0)

(goog-define server-host "")

(goog-define server-hosts "")

(goog-define connect-timeout 1000)

(def selected-host nil)

(goog-define server-port 8200)

(goog-define repl-pprint false)

(goog-define use-document-host true)

(goog-define use-document-protocol false)

(goog-define devtools-url "")

(goog-define reload-strategy "optimized")

(goog-define server-token "missing")

(goog-define ssl false)

(goog-define ignore-warnings false)

(goog-define log-style "font-weight: bold;")

(goog-define custom-notify-fn "")

(defn devtools-info []
  #js {:server_port server-port
       :server_host server-host
       :build_id build-id
       :proc_id proc-id
       :runtime_id runtime-id
       :ssl ssl})

(defn get-server-protocol []
  (if (and use-document-protocol
           js/goog.global.location
           (seq js/goog.global.location.protocol))
    (str/replace js/goog.global.location.protocol ":" "")
    (str "http" (when ssl "s"))))

(defn get-server-host []
  (cond
    (seq selected-host)
    selected-host

    (and use-document-host
         js/goog.global.location
         (seq js/goog.global.location.hostname))
    js/goog.global.location.hostname

    (seq server-host)
    server-host

    :else
    "localhost"))

(defn get-url-base []
  (if (seq devtools-url)
    devtools-url
    (str (get-server-protocol) "://" (get-server-host) ":" server-port)))

(defn get-ws-url-base []
  (-> (get-url-base)
      (str/replace #"^http" "ws")))

(defn get-ws-relay-path []
  (str "/api/remote-relay?server-token=" server-token))

(defn get-ws-relay-url []
  (str (get-ws-url-base) (get-ws-relay-path)))

;; FIXME: this need to become idempotent somehow
;; but is something sets a print-fn we can't tell if that
;; will actually call ours. only a problem if the websocket is
;; reconnected though
(defonce reset-print-fn-ref (atom nil))
(defonce was-print-newline *print-newline*)

(defn set-print-fns! [msg-fn]
  ;; cannot capture these before as they may change in between loading this file
  ;; and running the websocket connect. the user code is loaded after this file
  (let [original-print-fn cljs.core/*print-fn*
        original-print-err-fn cljs.core/*print-err-fn*]

    (set! *print-newline* true)

    ;; just prevent user code calling it, shadow-cljs setup code already did
    (set! js/cljs.core.enable-console-print! (fn []))

    (reset! reset-print-fn-ref
      (fn reset-print-fns! []
        (set! *print-newline* was-print-newline)
        (set-print-fn! original-print-fn)
        (set-print-err-fn! original-print-err-fn)))

    (set-print-fn!
      (fn repl-print-fn [s]
        (msg-fn :stdout s)
        (when (and original-print-fn (not= s "\n"))
          (original-print-fn s))))

    (set-print-err-fn!
      (fn repl-print-err-fn [s]
        (msg-fn :stderr s)
        (when (and original-print-err-fn (not= s "\n"))
          (original-print-err-fn s))))))

(defn reset-print-fns! []
  (when-let [x @reset-print-fn-ref]
    (x)
    (reset! reset-print-fn-ref nil)))

(defn patch-goog! []
  ;; patch away the already declared exception and checks
  ;; otherwise hot-reload may fail
  (set! js/goog.provide js/goog.constructNamespace_)
  ;; also override goog.require to just return the namespace
  ;; which is needed inside goog.module modules. otherwise
  ;; the return is ignored anyways.
  ;; this isn't strictly needed but ensures that loading
  ;; actually only does what we want and not more
  (set! js/goog.require js/goog.module.get))

(defn add-warnings-to-info [{:keys [info] :as msg}]
  (let [warnings
        (->> (for [{:keys [resource-name warnings] :as src} (:sources info)
                   :when (not (:from-jar src))
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (distinct)
             (into []))]
    (assoc-in msg [:info :warnings] warnings)))

(def custom-notify-types
  #{:build-complete
    :build-failure
    :build-init
    :build-start})

(defn run-custom-notify! [msg]
  ;; look up every time it case it gets reloaded
  (when (seq custom-notify-fn)
    (let [fn (js/goog.getObjectByName custom-notify-fn js/$CLJS)]
      (if-not (fn? fn)
        (js/console.warn "couldn't find custom :build-notify" custom-notify-fn)
        (try
          (fn msg)
          (catch :default e
            (js/console.error "Failed to run custom :build-notify" custom-notify-fn)
            (js/console.error e)))))))

(defn make-task-fn [{:keys [log-missing-fn log-call-async log-call]} {:keys [fn-sym fn-str async]}]
  (fn [next]
    (try
      (let [fn-obj (js/goog.getObjectByName fn-str js/$CLJS)]
        (cond
          (nil? fn-obj)
          (do (when log-missing-fn
                (log-missing-fn fn-sym))
              (next))

          async
          (do (when log-call-async
                (log-call-async fn-sym))
              (fn-obj next))

          :else
          (do (when log-call
                (log-call fn-sym))
              (fn-obj)
              (next))))
      (catch :default ex
        (js/console.warn "error when calling lifecycle function" (str fn-sym) ex)
        (next)))))

(defn do-js-reload* [failure-fn [task & remaining-tasks]]
  (when task
    (try
      (task #(do-js-reload* failure-fn remaining-tasks))
      (catch :default e
        (failure-fn e task remaining-tasks)))))

(defn do-js-reload
  "should pass the :build-complete message and an additional callback
   which performs the actual loading of the code (sync)
   will call all before/after callbacks in order"
  ([msg load-code-fn]
   (do-js-reload
     msg
     load-code-fn
     (fn [])))
  ([msg load-code-fn complete-fn]
   (do-js-reload
     msg
     load-code-fn
     complete-fn
     (fn [error task remaining]
       (js/console.warn "JS reload failed" error))))
  ([{:keys [reload-info] :as msg} load-code-fn complete-fn failure-fn]
   (let [load-tasks
         (-> []
             ;; unload is FILO
             (into (->> (:before-load reload-info)
                        (map #(make-task-fn msg %))
                        (reverse)))
             (conj load-code-fn)
             ;; load is FIFO
             (into (map #(make-task-fn msg %)) (:after-load reload-info))
             (conj (fn [next]
                     (complete-fn)
                     (next))))]

     (do-js-reload* failure-fn load-tasks))))

(defn before-load-src [{:keys [type ns] :as src}]
  (when (= :cljs type)
    (doseq [x js/goog.global.SHADOW_NS_RESET]
      (x ns))))

(defn goog-is-loaded? [name]
  (js/$CLJS.SHADOW_ENV.isLoaded name))

(def goog-base-rc
  [:shadow.build.classpath/resource "goog/base.js"])

(defn src-is-loaded? [{:keys [resource-id output-name] :as src}]
  ;; FIXME: don't like this special case handling, but goog/base.js will always be loaded
  ;; but not as a separate file
  (or (= goog-base-rc resource-id)
      (goog-is-loaded? output-name)))

(defn prefilter-sources [reload-info sources]
  (->> sources
       (filter
         (fn [{:keys [module] :as rc}]
           (or (= "js" module-format)
               (module-is-active? module))))
       ;; don't reload namespaces that have ^:dev/never-reload meta
       (remove (fn [{:keys [ns]}]
                 (contains? (:never-load reload-info) ns)))))

(defn filter-sources-to-get-optimized [{:keys [sources compiled] :as info} reload-info]
  (->> sources
       (prefilter-sources reload-info)
       (filter
         (fn [{:keys [ns resource-id] :as src}]
           (or (contains? (:always-load reload-info) ns)
               (not (src-is-loaded? src))
               (and (contains? compiled resource-id)
                    ;; never reload files from jar
                    ;; they can't be hot-swapped so the only way they get re-compiled
                    ;; is if they have warnings, which we can't to anything about
                    (not (:from-jar src))))))
       (into [])))

(defn filter-sources-to-get-full [{:keys [sources compiled] :as info} reload-info]
  (loop [affected #{}
         sources-to-get []
         [src & more] (prefilter-sources reload-info sources)]

    (if-not src
      sources-to-get
      (let [{:keys [ns resource-id deps provides]}
            src

            should-reload?
            (or (contains? (:always-load reload-info) ns)
                ;; always load sources that haven't been loaded yet
                ;; this fixes issues where a namespace is added to a build that has
                ;; dependencies that haven't been loaded yet but were compiled before
                (not (src-is-loaded? src))
                (and (or (contains? compiled resource-id)
                         (some affected deps))
                     ;; never reload files from jar
                     ;; they can't be hot-swapped so the only way they get re-compiled
                     ;; is if they have warnings, which we can't to anything about
                     (not (:from-jar src))))]

        (if-not should-reload?
          (recur affected sources-to-get more)
          (recur
            (set/union affected provides)
            (conj sources-to-get src)
            more))))))

(defn filter-reload-sources [info reload-info]
  (if (= "full" reload-strategy)
    (filter-sources-to-get-full info reload-info)
    (filter-sources-to-get-optimized info reload-info)))
