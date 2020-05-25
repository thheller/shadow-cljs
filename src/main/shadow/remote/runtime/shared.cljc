(ns shadow.remote.runtime.shared
  (:require
    [clojure.datafy :as d]
    [clojure.pprint :refer (pprint)]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.writer :as lw])
  #?(:clj (:import [java.util UUID])))

(defn init-state []
  {:extensions {}
   :ops {}
   :call-id-seq 0
   :call-handlers {}})

(defn now []
  #?(:cljs (js/Date.now)
     :clj (System/currentTimeMillis)))

(defn reply [runtime {:keys [mid tid]} res]
  (let [res (-> res
                (cond->
                  mid
                  (assoc :mid mid)
                  tid
                  (assoc :tid tid)))]
    (p/relay-msg runtime res)))

(defn call
  ([runtime msg handlers]
   (call runtime msg handlers 0))
  ([{:keys [state-ref] :as runtime}
    msg
    handlers
    timeout-after-ms]
   (let [mid (:call-id-seq @state-ref)]
     (swap! state-ref update :call-id-seq inc)
     (swap! state-ref assoc-in [:call-handlers mid]
       {:handlers handlers
        :called-at (now)
        :msg msg
        :timeout timeout-after-ms})
     (p/relay-msg runtime (assoc msg :mid mid)))))

(defn welcome
  [{:keys [state-ref]} {:keys [rid] :as msg}]
  #?(:cljs (js/console.log "shadow.remote - runtime-id:" rid))
  (swap! state-ref assoc :runtime-id rid))

(defn tool-disconnect
  [{:keys [state-ref]} {:keys [tid] :as msg}]
  (doseq [{:keys [on-tool-disconnect]} (-> @state-ref :extensions vals)
          :when on-tool-disconnect]
    (on-tool-disconnect tid)))

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
     :clj  (prn [:unknown-relay-op msg])))

(defn unknown-op [msg]
  #?(:cljs (js/console.warn "unknown-op" msg)
     :clj  (prn [:unknown-relay-op msg])))

(defn add-extension*
  [{:keys [extensions] :as state} key {:keys [ops] :as spec}]
  (when (contains? extensions key)
    (throw (ex-info "extension already registered" {:key key :spec spec})))

  (reduce-kv
    (fn [state op-kw op-handler]
      (when (get-in state [:ops op-kw])
        (throw (ex-info "op already registered" {:key key :op op-kw})))
      (assoc-in state [:ops op-kw] op-handler))

    (assoc-in state [:extensions key] spec)
    ops))

(defn add-extension [{:keys [state-ref]} key spec]
  (swap! state-ref add-extension* key spec))

(defn add-defaults [{:keys [state-ref] :as runtime}]
  (add-extension runtime
    ::defaults
    {:ops
     {:welcome #(welcome runtime %)
      :unknown-relay-op #(unknown-relay-op %)
      :unknown-op #(unknown-op %)
      :request-supported-ops #(request-supported-ops runtime %)
      :tool-disconnect #(tool-disconnect runtime %)}}))

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
     :clj  (prn [:unhandled-call-result msg])))

(defn reply-unknown-op [runtime msg]
  (reply runtime msg {:op :unknown-op
                      :msg msg}))

(defn process [{:keys [state-ref] :as runtime} {:keys [op mid] :as msg}]
  ;; (js/console.log "received from relay" msg)
  (let [state @state-ref
        op-handler (get-in state [:ops op])]
    ;; extension op handlers always come first
    (if op-handler
      (op-handler msg)
      ;; expecting rpc reply when mid is set
      (if mid
        (let [cfg (get-in state [:call-handlers mid])]
          (if-not cfg
            (reply-unknown-op runtime msg)
            ;; call may have not provided handler for op
            ;; not something the relay should care about, so don't tell it
            (if-some [op-handler (get-in cfg [:handlers op])]
              ;; only expecting one result for calls
              (do (swap! state-ref update :call-handlers dissoc mid)
                  (op-handler msg))
              ;; no handler set
              (unhandled-call-result cfg msg))))

        ;; FIXME: unknown-mid?
        (reply-unknown-op runtime msg)))))

(defn run-on-idle [state-ref]
  (doseq [{:keys [on-idle]} (-> @state-ref :extensions vals)
          :when on-idle]
    (on-idle)))