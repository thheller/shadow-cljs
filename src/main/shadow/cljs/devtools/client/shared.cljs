(ns shadow.cljs.devtools.client.shared
  (:require
    [goog.object :as gobj]
    [cognitect.transit :as transit]
    [clojure.set :as set]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.eval-support :as eval-support]))

(defprotocol IRemote
  (remote-open [this e])
  (remote-msg [this msg])
  (remote-close [this e info])
  (remote-error [this e]))

(defprotocol IHostSpecific
  (do-repl-init [this action done error])
  (do-repl-require [this require-msg done error])
  (do-invoke [this invoke-msg]))

(defn load-sources [runtime sources callback]
  (shared/call runtime
    {:op :cljs-load-sources
     :to env/worker-client-id
     :sources (into [] (map :resource-id) sources)}
    {:cljs-sources
     (fn [{:keys [sources] :as msg}]
       (callback sources))}))

(defonce runtime-ref (atom nil))
(defonce plugins-ref (atom {}))

(defn start-all-plugins! [{:keys [state-ref] :as runtime}]
  (let [started-set (set (keys (::plugins @state-ref)))
        plugins @plugins-ref
        plugins-set (set (keys plugins))
        pending-set (set/difference plugins-set started-set)]

    ;; FIXME: this is dumb, should properly sort things in dependency order
    ;; instead of looping over
    (loop [pending-set pending-set]
      (cond
        (empty? pending-set)
        ::done!

        :else
        (-> (reduce
              (fn [pending-set plugin-id]
                (let [{:keys [depends-on init-fn] :as plugin} (get plugins plugin-id)]
                  (if (some pending-set depends-on)
                    pending-set
                    (let [start-arg (assoc (select-keys (::plugins @state-ref) depends-on) :runtime runtime)
                          started (init-fn start-arg)]
                      (swap! state-ref assoc-in [::plugins plugin-id] started)
                      (disj pending-set plugin-id)))))
              pending-set
              pending-set)
            (recur))))))

;; generic plugin mechanism
;; runtime already has extensions but requires access to runtime
;; plugin decouple the lifecycle so they can be created wherever
(defn add-plugin!
  [plugin-id depends-on init-fn stop-fn]
  {:pre [(keyword? plugin-id)
         (set? depends-on)
         (fn? init-fn)
         (fn? stop-fn)]}

  (when-some [runtime @runtime-ref]
    (when-some [started (get-in runtime [::plugins plugin-id])]
      (let [{:keys [stop-fn] :as old} (get @plugins-ref plugin-id)]
        (stop-fn started)
        (swap! runtime-ref update ::plugins dissoc plugin-id))))

  (swap! plugins-ref assoc plugin-id
    {:ext-id plugin-id
     :depends-on depends-on
     :init-fn init-fn
     :stop-fn stop-fn})

  ;; in case runtime is already started
  (when-some [runtime @runtime-ref]
    (start-all-plugins! runtime)))

(defn transit-read [data]
  (let [t (transit/reader :json)]
    (transit/read t data)))

(defn transit-str [obj]
  (let [w (transit/writer :json)]
    (transit/write w obj)))

(declare interpret-actions)

(defn continue! [state]
  (interpret-actions state))

(defn abort! [{:keys [callback] :as state} action ex]
  (-> state
      (assoc :result :runtime-error
             :ex ex
             :ex-action action)
      (dissoc :runtime :callback)
      (callback)))

(defn handle-invoke [state runtime action]
  (let [res (do-invoke runtime action)]
    (update state :results conj res)))

(defn handle-repl-invoke [state runtime action]
  (try
    (let [ret (do-invoke runtime action)]

      ;; FIXME: these are nonsense with multiple sessions. refactor this properly
      (set! *3 *2)
      (set! *2 *1)
      (set! *1 ret)

      (if (:internal action)
        state
        (update state :results conj ret)))

    (catch :default e
      (set! *e e)
      (throw e))))

(defn interpret-action
  [{:keys [runtime] :as state}
   {:keys [type] :as action}]
  (case type
    :repl/init
    (do-repl-init runtime action
      (fn []
        (swap! (:state-ref runtime) assoc :init-complete true)
        (continue! state))
      (fn [ex]
        (abort! state action ex)))

    :repl/set-ns
    (let [{:keys [ns]} action]
      (-> state
          (assoc :ns ns)
          (update :results conj nil)
          (continue!)))

    :repl/require
    (let [{:keys [internal]} action]
      (do-repl-require runtime action
        (fn [sources]
          (-> state
              (update :loaded-sources into sources)
              (cond->
                ;; (require '...) has a result
                ;; (ns foo.bar (:require ...)) does not since ns has the result
                (not internal)
                (update :results conj nil))
              (continue!)))
        (fn [ex]
          (abort! state action ex))))

    :repl/invoke
    (try
      (let [repl (get-in state [:input :repl])]
        (-> state
            (cond->
              repl
              (handle-repl-invoke runtime action)
              (not repl)
              (handle-invoke runtime action))
            (continue!)))
      (catch :default ex
        (abort! state action ex)))

    (throw (ex-info "unhandled repl action" {:state state :action action}))))

(defn interpret-actions [{:keys [queue warnings] :as state}]
  (cond
    (empty? queue)
    (let [{:keys [callback]} state]
      (-> state
          (dissoc :runtime :callback :queue)
          (assoc :time-finish (js/Date.now))
          (callback)))

    (and (seq warnings) (false? env/ignore-warnings))
    (let [{:keys [callback]} state]
      (-> state
          (dissoc :runtime :callback :queue)
          (assoc :result :warnings
                 :warnings warnings
                 :time-finish (js/Date.now))
          (callback)))

    :else
    (let [action (first queue)
          state (update state :queue rest)]
      (interpret-action state action))))

(defn setup-actions [runtime input {:keys [actions] :as msg} callback]
  {:runtime runtime
   :callback callback
   :input input
   :msg msg
   :time-start (js/Date.now) ;; time used for prepl
   :queue actions
   :result :ok
   :results []
   :ns (:ns input)
   :warnings
   (->> actions
        (mapcat :warnings)
        (vec))
   :loaded-sources []})

(defrecord Runtime [state-ref]
  api/IRuntime
  (relay-msg [this msg]
    (let [{::keys [ws-state ws-connected ws-send-fn] :as state} @state-ref]
      (if-not ws-connected
        (js/console.warn "shadow-cljs - dropped ws message, not connected" msg state)
        (let [s (try
                  (transit-str msg)
                  (catch :default e
                    (throw (ex-info "failed to encode relay msg" {:msg msg}))))]
          ;; (js/console.log "sending" msg state)
          (ws-send-fn ws-state s)))))

  (add-extension [runtime key spec]
    (shared/add-extension runtime key spec))
  (del-extension [runtime key]
    (shared/del-extension runtime key))

  api/IEvalCLJS
  (-cljs-eval [this input callback]
    ;; FIXME: define what input is supposed to look like
    ;; {:code "(some-cljs)" :ns foo.bar}
    (shared/call this
      {:op :cljs-compile
       :to env/worker-client-id
       :input input
       :include-init (not (:init-complete @state-ref))}

      {:cljs-compile-result
       (fn [msg]
         (-> (setup-actions this input msg callback)
             (interpret-actions)))

       ;; going from cljs specific error
       :cljs-compile-error
       (fn [{:keys [ex-oid ex-client-id ex-data report]}]
         (callback
           ;; to more generic error, that CLJ can also use
           {:result :compile-error
            :ex-oid ex-oid
            :ex-client-id ex-client-id
            :ex-data ex-data
            :report report}))

       :client-not-found
       (fn [msg]
         (callback
           {:result :worker-not-found}))}))

  IRemote
  (remote-open [this e]
    ;; (js/console.log "runtime remote-open" this e)
    (swap! state-ref assoc
      ::ws-errors 0
      ::ws-connecting false
      ::ws-connected true
      ::ws-last-msg (shared/now)))

  (remote-msg [this text]
    (let [msg (transit-read text)]
      ;; (js/console.log "runtime remote-msg" this msg)
      (swap! state-ref assoc ::ws-last-msg (shared/now))
      (when (= :access-denied (:op msg))
        (swap! state-ref assoc ::stale true))
      (shared/process this msg)))

  (remote-close [this e info]
    ;; (js/console.log "runtime remote-close" @state-ref e)
    (swap! state-ref dissoc ::ws-connected ::ws-connecting)

    ;; after 3 failed attempts just stop
    (if (>= 3 (::ws-errors @state-ref))
      (.schedule-connect! this 5000)
      (js/console.warn "shadow-cljs: giving up trying to connect to " info)))

  (remote-error [this e]
    (swap! state-ref update ::ws-errors inc)

    (shared/trigger! this :on-disconnect)

    (js/console.error "shadow-cljs - remote-error" e))

  Object
  (attempt-connect! [this]
    (let [{::keys [ws-connecting ws-connect-timeout shutdown stale ws-state ws-stop-fn ws-start-fn]
           :as state}
          @state-ref]

      ;; (js/console.log "attempt-connect!" state)
      (when (and (not shutdown)
                 (not stale)
                 (not ws-connecting))

        (when ws-connect-timeout
          (js/clearTimeout ws-connect-timeout))

        (when (some? ws-state)
          (ws-stop-fn ws-state))

        (let [ws-state (ws-start-fn this)]
          (swap! state-ref assoc
            ::ws-connecting true
            ::ws-connected false
            ::ws-state ws-state)))))

  (schedule-connect! [this after]
    ;; (js/console.log "scheduling next connect" after @state-ref)
    (let [{::keys [ws-connect-timeout stale shutdown]} @state-ref]
      (when ws-connect-timeout
        (js/clearTimeout ws-connect-timeout))

      (when (and (not stale) (not shutdown))
        (shared/trigger! this :on-reconnect)

        (swap! state-ref assoc
          ::ws-connect-timeout
          (js/setTimeout
            (fn []
              ;; (js/console.log "attempt-connect after schedule timeout" @state-ref)
              (swap! state-ref dissoc ::ws-connect-timeout)
              (.attempt-connect! this))
            after))))))

(defonce print-subs (atom #{}))

(defn stop-runtime! [{:keys [state-ref] :as runtime}]
  (let [{::keys [ws-state ws-stop-fn interval plugins]} @state-ref]

    (js/clearInterval interval)

    (when (some? ws-state)
      (ws-stop-fn ws-state))

    (reduce-kv
      (fn [_ plugin-id started]
        ;; FIXME: should stop in reverse started order
        (let [{:keys [stop-fn]} (get @plugins-ref plugin-id)]
          (stop-fn started)))
      nil
      plugins)

    (swap! state-ref assoc ::shutdown true)))

(defn init-runtime! [client-info ws-start-fn ws-send-fn ws-stop-fn]
  ;; in case of hot-reload or reconnect, clean up previous runtime
  (when-some [runtime @runtime-ref]
    (stop-runtime! runtime)
    (reset! runtime-ref nil))

  (add-plugin! :obj-support #{}
    #(obj-support/start (:runtime %))
    obj-support/stop)

  (add-plugin! :tap-support #{:obj-support}
    (fn [{:keys [runtime obj-support]}]
      (tap-support/start runtime obj-support))
    tap-support/stop)

  (add-plugin! :eval-support #{:obj-support}
    (fn [{:keys [runtime obj-support]}]
      (eval-support/start runtime obj-support))
    eval-support/stop)

  (let [state-ref
        (-> (assoc client-info
              :type :runtime
              :lang :cljs
              :build-id (keyword env/build-id)
              :proc-id env/proc-id)
            (shared/init-state)
            (assoc ::shutdown false
                   ::stale false
                   ::plugins {}
                   ::ws-errors 0
                   ::ws-start-fn ws-start-fn
                   ::ws-send-fn ws-send-fn
                   ::ws-stop-fn ws-stop-fn)
            (atom))

        runtime
        (doto (->Runtime state-ref)
          (shared/add-defaults))

        idle-fn
        (fn []
          (let [{::keys [shutdown ws-connected ws-last-msg ws-connect-timeout] :as state} @state-ref]
            (when (and (not ws-connect-timeout) (not shutdown) ws-connected (> (shared/now) (+ ws-last-msg 20000)))
              ;; should be receiving pings, if not assume dead ws
              ;; (js/console.log "attempting reconnect because of idle" state)
              ;; wait a little, otherwise might get ERR_INTERNET_DISCONNECTED after waking from sleep
              (swap! state-ref dissoc ::ws-connected)
              (.schedule-connect! runtime 2000))

            (shared/run-on-idle state-ref)))]

    (swap! state-ref assoc ::interval (js/setInterval idle-fn 1000))

    (reset! runtime-ref runtime)

    ;; test exporting this into the global so potential consumers
    ;; don't have to worry about importing a namespace that shouldn't be in release builds
    ;; can't bind cljs.core/eval since that expects a CLJ form not a string
    ;; which we could technically also support but I don't want to assume the user
    ;; knows how to read properly. just accepting a string and optional ns is much easier
    (set! js/goog.global.cljs_eval
      (fn [input opts]
        (let [input
              (cond
                ;; preferred when calling from CLJS
                (map? input)
                input

                ;; just calling with code
                (and (string? input) (not opts))
                {:code input :ns 'cljs.user}

                ;; when calling from JS {ns: "cljs.user"}
                ;; FIXME: other opts?
                (and (string? input) (object? opts))
                {:code input :ns (symbol (gobj/get opts "ns"))}

                :else
                (throw (ex-info "invalid arguments, call cljs_eval(string, opts-obj) or cljs_eval(map)" {:input input :opts opts})))]

          (js/Promise.
            (fn [resolve reject]
              (api/cljs-eval runtime input
                (fn [{:keys [result results] :as info}]
                  (if (= :ok result)
                    ;; FIXME: option to not throw away multiple results?
                    ;; user may do cljs_eval("1 2 3") and will only get 3 but we have [1 2 3]
                    (resolve (last results))
                    (reject info)))))))))

    (api/add-extension runtime
      ::print-support
      {:ops
       {:runtime-print-sub
        (fn [{:keys [from] :as msg}]
          (swap! print-subs conj from)
          (shared/relay-msg runtime
            {:op :request-notify
             :notify-op ::runtime-print-disconnect
             :query [:eq :client-id from]}))
        :runtime-print-unsub
        (fn [{:keys [from] :as msg}]
          (swap! print-subs disj from))
        ::runtime-print-disconnect
        (fn [{:keys [event-op client-id]}]
          (when (= :client-disconnect event-op)
            (swap! print-subs disj client-id)))}

       ;; just in case the disconnect notify comes after trying to send something
       :on-client-not-found
       (fn [{:keys [client-id]}]
         (swap! print-subs disj client-id))})

    ;; in case this was hot reloaded, restore previous state first
    (env/reset-print-fns!)

    (env/set-print-fns!
      (fn [stream text]
        (let [subs @print-subs]
          ;; (js/console.log "print" stream text subs)
          (when (seq subs)
            (shared/relay-msg runtime
              {:op :runtime-print
               :to subs
               :stream stream
               :text text})))))

    (api/add-extension runtime
      ::shared
      {:on-welcome
       (fn []
         (shared/relay-msg runtime
           {:op :request-notify
            :notify-op ::env/worker-notify
            :query [:eq :shadow.cljs.model/worker-for (keyword env/build-id)]}))})

    (start-all-plugins! runtime)

    ;; (js/console.log "first connect from init-runtime!")
    (.attempt-connect! runtime)))

