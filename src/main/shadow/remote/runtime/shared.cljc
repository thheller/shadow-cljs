(ns shadow.remote.runtime.shared
  (:require
    [shadow.remote.runtime.api :as p]
    #?@(:clj
        [[shadow.jvm-log :as log]]
        :cljs
        [])))

(defn init-state [client-info]
  {:extensions {}
   :ops {}
   :client-info client-info
   :call-id-seq 0
   :call-handlers {}})

(declare process)

(defn now []
  #?(:cljs (js/Date.now)
     :clj (System/currentTimeMillis)))

(defn get-client-id [{:keys [state-ref] :as runtime}]
  (or (:client-id @state-ref)
      (throw (ex-info "runtime has no assigned runtime-id" {:runtime runtime}))))

(defn relay-msg [runtime msg]
  (let [self-id (get-client-id runtime)]
    ;; check if sending msg to ourselves, then we don't need to bother the relay
    ;; FIXME: might be better to do this in p/relay-msg?
    (if (not= (:to msg) self-id)
      (p/relay-msg runtime msg)
      ;; don't immediately process, the relay hop is async, so preserve that
      ;; this is sort of hacky for messages that we are actually sending ourselves
      ;; should at least send to the same queue the ws messages end up in?
      #?(:clj
         (future (process runtime (assoc msg :from self-id)))
         :cljs
         (-> (js/Promise.resolve 1)
             (.then #(process runtime (assoc msg :from self-id)))))))

  ;; just so nobody assumes this has a useful return value
  msg)

(defn reply [runtime {:keys [call-id from]} res]
  (let [res (-> res
                (cond->
                  call-id
                  (assoc :call-id call-id)
                  from
                  (assoc :to from)))]
    (p/relay-msg runtime res)))

(defn call
  ([runtime msg handlers]
   (call runtime msg handlers 0))
  ([{:keys [state-ref] :as runtime}
    msg
    handlers
    timeout-after-ms]
   {:pre [(map? msg)
          (map? handlers)
          (nat-int? timeout-after-ms)]}
   (let [call-id (:call-id-seq @state-ref)]
     (swap! state-ref update :call-id-seq inc)
     (swap! state-ref assoc-in [:call-handlers call-id]
       {:handlers handlers
        :called-at (now)
        :msg msg
        :timeout timeout-after-ms})
     (p/relay-msg runtime (assoc msg :call-id call-id)))))

(defn trigger! [{:keys [state-ref] :as runtime} ev & args]
  (doseq [ext (vals (:extensions @state-ref))
          :let [ev-fn (get ext ev)]
          :when ev-fn]
    (apply ev-fn args)))

(defn welcome
  [{:keys [state-ref] :as runtime} {:keys [client-id] :as msg}]
  ;; #?(:cljs (js/console.log "shadow.remote - runtime-id:" rid))
  (swap! state-ref assoc :client-id client-id :welcome true)

  (let [{:keys [client-info extensions]} @state-ref]
    (relay-msg runtime
      {:op :hello
       :client-info client-info})

    (trigger! runtime :on-welcome)))

(defn ping
  [runtime msg]
  (reply runtime msg {:op :pong}))

(defn request-supported-ops
  [{:keys [state-ref] :as runtime} msg]
  (reply runtime msg
    {:op :supported-ops
     :ops (-> (:ops @state-ref)
              (keys)
              (set)
              (disj :welcome :unknown-relay-op :unknown-op :request-supported-ops :tool-disconnect))}))

(defn unknown-relay-op [msg]
  #?(:cljs (js/console.warn "unknown-relay-op" msg)
     :clj (log/warn ::unknown-relay-op msg)))

(defn unknown-op [msg]
  #?(:cljs (js/console.warn "unknown-op" msg)
     :clj (log/warn ::unknown-op msg)))

(defn add-extension*
  [{:keys [extensions] :as state} key {:keys [ops transit-write-handlers] :as spec}]
  (when (contains? extensions key)
    (throw (ex-info "extension already registered" {:key key :spec spec})))

  (reduce-kv
    (fn [state op-kw op-handler]
      (when (get-in state [:ops op-kw])
        (throw (ex-info "op already registered" {:key key :op op-kw})))
      (assoc-in state [:ops op-kw] op-handler))

    (assoc-in state [:extensions key] spec)
    ops))

(defn add-extension [{:keys [state-ref] :as runtime} key spec]
  (swap! state-ref add-extension* key spec)

  ;; trigger on-welcome immediately if already welcome was already received
  (when-some [on-welcome (:on-welcome spec)]
    (when (:welcome @state-ref)
      (on-welcome)))

  runtime)

(defn add-defaults [runtime]
  (add-extension runtime
    ::defaults
    {:ops
     {:welcome #(welcome runtime %)
      :unknown-relay-op #(unknown-relay-op %)
      :unknown-op #(unknown-op %)
      :ping #(ping runtime %)
      :request-supported-ops #(request-supported-ops runtime %)
      }}))

(defn del-extension* [state key]
  (let [ext (get-in state [:extensions key])]
    (if-not ext
      state
      (reduce-kv
        (fn [state op-kw op-handler]
          (update-in state [:ops] dissoc op-kw))

        (update state :extensions dissoc key)
        (:ops ext)))))

(defn del-extension [{:keys [state-ref]} key]
  (swap! state-ref del-extension* key))

(defn unhandled-call-result [call-config msg]
  #?(:cljs (js/console.warn "unhandled call result" msg call-config)
     :clj (log/warn ::unhandled-call-result msg)))

(defn unhandled-client-not-found
  [{:keys [state-ref] :as runtime} msg]
  (trigger! runtime :on-client-not-found msg))

(defn reply-unknown-op [runtime msg]
  (reply runtime msg {:op :unknown-op
                      :msg msg}))

(defn process [{:keys [state-ref] :as runtime} {:keys [op call-id] :as msg}]
  ;; (js/console.log "received from relay" msg)
  (let [state @state-ref
        op-handler (get-in state [:ops op])]

    (cond
      ;; expecting rpc reply when mid is set
      call-id
      (let [cfg (get-in state [:call-handlers call-id])
            call-handler (get-in cfg [:handlers op])]

        ;; replies may either go to registered call handler
        ;; or if that is missing to a global op handler
        (cond
          call-handler
          (do (swap! state-ref update :call-handlers dissoc call-id)
              (call-handler msg))

          op-handler
          (op-handler msg)

          ;; nothing here to handle it
          :else
          (unhandled-call-result cfg msg)))

      op-handler
      (op-handler msg)

      ;; don't want to reply with unknown-op to client-not-found
      (= :client-not-found op)
      (unhandled-client-not-found runtime msg)

      :else
      (reply-unknown-op runtime msg))))

(defn run-on-idle [state-ref]
  (doseq [{:keys [on-idle]} (-> @state-ref :extensions vals)
          :when on-idle]
    (on-idle)))