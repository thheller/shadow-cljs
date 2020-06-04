(ns shadow.cljs.devtools.client.shared
  (:require
    [goog.object :as gobj]
    [cognitect.transit :as transit]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.eval-support :as eval-support]
    [clojure.set :as set]
    [shadow.remote.runtime.api :as p]))

(defprotocol IHostSpecific
  (do-repl-init [this action done error])
  (do-repl-require [this require-msg done error])
  (do-invoke [this invoke-msg]))

(defonce runtime-ref (atom nil))
(defonce extensions-ref (atom {}))

(defn start-all-extensions! []
  (let [started-set (set (keys @runtime-ref))
        exts @extensions-ref
        ext-set (set (keys exts))
        pending-set (set/difference ext-set started-set)]

    ;; FIXME: this is dumb, should properly sort things in dependency order
    ;; instead of looping over
    (loop [pending-set pending-set]
      (cond
        (empty? pending-set)
        ::done!

        :else
        (-> (reduce
              (fn [pending-set ext-id]
                (let [{:keys [depends-on init-fn] :as ext} (get exts ext-id)]
                  (if (some pending-set depends-on)
                    pending-set
                    (let [started (init-fn @runtime-ref)]
                      (swap! runtime-ref assoc ext-id started)
                      (disj pending-set ext-id)))))
              pending-set
              pending-set)
            (recur))))))

;; generic extension mechanism for things that don't have access to the runtime
;; and don't want to worry about the lifecycle
(defn init-extension! [ext-id depends-on init-fn stop-fn]
  (when-some [started (get @runtime-ref ext-id)]
    (let [{:keys [stop-fn] :as old} (get @extensions-ref ext-id)]
      (stop-fn started)
      (swap! runtime-ref dissoc ext-id)))

  (swap! extensions-ref assoc ext-id {:ext-id ext-id
                                      :depends-on depends-on
                                      :init-fn init-fn
                                      :stop-fn stop-fn})

  ;; in case runtime is already started
  (when @runtime-ref
    (start-all-extensions!)))


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

      (update state :results conj ret))

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
    (-> state
        (assoc :ns (:ns action))
        (continue!))

    :repl/require
    (let [{:keys [internal]} action]
      (do-repl-require runtime action
        (fn [sources]
          (-> state
              (update :loaded-sources into sources)
              (cond->
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

    ;; FIXME: let client input decide if eval should happen regardless of warnings
    (seq warnings)
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

(defrecord Runtime [state-ref send-fn close-fn]
  api/IRuntime
  (relay-msg [this msg]
    (let [s (try
              (transit-str msg)
              (catch :default e
                (throw (ex-info "failed to encode relay msg" {:msg msg}))))]
      (send-fn s)))

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
       (fn [{:keys [ex-oid ex-client-id report]}]
         (callback
           ;; to more generic error, that CLJ can also use
           {:result :compile-error
            :ex-oid ex-oid
            :ex-client-id ex-client-id
            :report report}))

       :client-not-found
       (fn [msg]
         (callback
           {:result :worker-not-found}))})))

(defonce print-subs (atom #{}))

(defn init-runtime! [{:keys [state-ref close-fn] :as runtime}]
  (shared/add-defaults runtime)

  (let [obj-support
        (obj-support/start runtime)

        tap-support
        (tap-support/start runtime obj-support)

        eval-support
        (eval-support/start runtime obj-support)

        interval
        (js/setInterval #(shared/run-on-idle state-ref) 1000)

        stop
        (fn []
          (js/clearTimeout interval)
          (eval-support/stop eval-support)
          (tap-support/stop tap-support)
          (obj-support/stop obj-support)
          (close-fn))]

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

    (reset! runtime-ref
      {:runtime runtime
       :obj-support obj-support
       :tap-support tap-support
       :eval-support eval-support
       :stop stop})

    (p/add-extension runtime
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

    (p/add-extension runtime
      ::shared
      {:on-connect
       (fn []
         (shared/relay-msg runtime
           {:op :request-notify
            :notify-op ::env/worker-notify
            :query [:eq :shadow.cljs.model/worker-for (keyword env/build-id)]}))})

    (when (seq @extensions-ref)
      (start-all-extensions!))))

(defn stop-runtime! []
  (when-some [runtime @runtime-ref]
    (shared/trigger-on-disconnect! (:runtime runtime))
    (reset! runtime-ref nil)
    (let [{:keys [stop]} runtime]
      (stop))))

(defn cljs-repl-ping
  [runtime {:keys [from time-server] :as msg}]
  ;; (js/console.log "cljs-repl-ping" msg)
  (shared/relay-msg runtime
    {:op :cljs-repl-pong
     :to from
     :time-server time-server
     :time-runtime (js/Date.now)}))