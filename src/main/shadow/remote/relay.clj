(ns shadow.remote.relay
  (:require
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [shadow.jvm-log :as log])
  (:import [java.util Date]))

(defn maybe-add-msg-id [{:keys [msg-id] :as req} res]
  (cond-> res
    msg-id
    (assoc :msg-id msg-id)))

(defmulti handle-sys-msg
  ;; origin is either a runtime or a tool
  (fn [state-ref origin msg] (:op msg ::default))
  :default ::default)

(defmethod handle-sys-msg ::default
  [state-ref origin {:keys [op] :as msg}]
  (>!! (:to origin) (maybe-add-msg-id msg {:op :unknown-op
                                           :request-op op})))

(defn send-to-tools [state-ref msg]
  (doseq [{:keys [to]} (-> @state-ref :tools vals)]
    (>!! to msg)))

(defn send-to-runtimes [state-ref msg]
  (doseq [{:keys [to]} (-> @state-ref :runtimes vals)]
    (>!! to msg)))

(defn handle-runtime-msg
  [state-ref {:keys [runtime-id] :as runtime} {:keys [tool-broadcast tool-id] :as msg}]
  (log/debug ::runtime-msg msg)
  (cond
    ;; only send to specific tool
    tool-id
    (let [tool (get-in @state-ref [:tools tool-id])]
      (if-not tool
        (>!! (:to runtime)
             (maybe-add-msg-id msg {:op :tool-not-found :tool-id tool-id}))
        (>!! (:to tool)
             (-> msg
                 (dissoc :tool-id)
                 (assoc :runtime-id runtime-id)))))

    tool-broadcast
    (send-to-tools state-ref (assoc msg :runtime-id runtime-id))

    :else
    (handle-sys-msg state-ref runtime msg)))

(defn handle-tool-msg
  [state-ref {:keys [tool-id] :as tool} {:keys [runtime-id runtime-broadcast] :as msg}]
  (log/debug ::tool-msg msg)
  (cond
    ;; client did send :runtime-id, forward to runtime if found
    runtime-id
    (let [runtime (get-in @state-ref [:runtimes runtime-id])]
      (if-not runtime
        (>!! (:to tool)
             (maybe-add-msg-id msg {:op :runtime-not-found
                                    :runtime-id runtime-id}))

        ;; forward with tool-id only, replies should be coming from runtime-out
        (>!! (:to runtime)
             (-> msg
                 (assoc :tool-id tool-id)
                 (dissoc :runtime-id)))))

    ;; FIXME: broadcast may not be a good idea, tools or runtimes can always iterate themselves
    runtime-broadcast
    (send-to-runtimes state-ref (assoc msg :tool-id tool-id))

    ;; treat as system op
    :else
    (handle-sys-msg state-ref tool msg)))

(defn runtime-connect [svc from-runtime runtime-info]
  (let [{:keys [state-ref id-seq-ref]} svc

        runtime-id (swap! id-seq-ref inc)

        to-runtime
        (async/chan 10)

        runtime-info
        (assoc runtime-info :since (Date.) :runtime-id runtime-id)

        runtime
        {:runtime-id runtime-id
         :runtime-info runtime-info
         :to to-runtime
         :from from-runtime}]

    (swap! state-ref assoc-in [:runtimes runtime-id] runtime)

    (send-to-tools state-ref {:op :runtime-connect
                              :runtime-id runtime-id
                              :runtime-info runtime-info})

    (go (loop []
          (when-some [msg (<! from-runtime)]
            (handle-runtime-msg state-ref runtime msg)
            (recur)))

        (send-to-tools state-ref {:op :runtime-disconnect
                                  :runtime-id runtime-id})

        (swap! state-ref update :runtimes dissoc runtime-id))

    (>!! to-runtime {:op :welcome
                     :runtime-id runtime-id})

    to-runtime
    ))

(defn tool-connect
  [svc from-tool]
  (let [{:keys [state-ref id-seq-ref]} svc

        tool-id (swap! id-seq-ref inc)

        to-tool
        (async/chan 10)

        tool-data
        {:tool-id tool-id
         :from from-tool
         :to to-tool}]

    (swap! state-ref assoc-in [:tools tool-id] tool-data)

    (go (loop []
          (when-some [msg (<! from-tool)]
            (handle-tool-msg state-ref tool-data msg)
            (recur)))

        ;; send to all runtimes so they can cleanup state?
        (send-to-runtimes state-ref {:op :tool-disconnect
                                     :tool-id tool-id})

        (swap! state-ref update :tools dissoc tool-id)
        (async/close! to-tool))

    (>!! to-tool {:op :welcome
                  :tool-id tool-id})

    ;; FIXME: could return the id right here with the channel?
    to-tool))

(defn start []
  {::service true
   :id-seq-ref (atom 0)
   :state-ref (atom {:tools {}
                     :runtimes {}})})

(defn stop [{:keys [state-ref] :as svc}]
  (let [{:keys [tools runtimes]} @state-ref]
    (doseq [{:keys [to]} (vals runtimes)]
      (async/close! to))
    (doseq [{:keys [to]} (vals tools)]
      (async/close! to))
    ))

(defmethod handle-sys-msg :request-runtimes
  [state-ref tool msg]
  (let [{:keys [runtimes]} @state-ref
        result (->> runtimes
                    (vals)
                    (map :runtime-info)
                    (vec))]
    (>!! (:to tool)
         (maybe-add-msg-id msg {:op :runtimes
                                :runtimes result}))))

(comment
  (def svc (start))
  (def svc (:relay (shadow.cljs.devtools.server.runtime/get-instance)))

  svc

  (stop svc)

  (def tool-in (async/chan))
  (def tool-out (tool-connect svc tool-in))

  (def tool-id-ref (atom nil))

  (go (loop []
        (when-some [msg (<! tool-out)]
          (prn :tool-out)
          (clojure.pprint/pprint msg)
          (when (= :welcome (:op :msg))
            (reset! tool-id-ref (:tool-id msg)))
          (recur)))
      (prn :tool-out-shutdown))

  (def runtime-in (async/chan))
  (def runtime-out (runtime-connect svc runtime-in {}))

  (go (loop []
        (when-some [msg (<! runtime-out)]
          (prn :runtime-out)
          (clojure.pprint/pprint msg)
          (recur)))
      (prn :runtime-out-shutdown))

  (require '[shadow.remote.runtime.clojure :as clj])

  (def clj (clj/start svc))
  (def clj (:clj-runtime (shadow.cljs.devtools.server.runtime/get-instance)))

  clj

  (clj/stop clj)

  (>!! tool-in {:op :request-runtimes})

  (>!! tool-in {:op :request-supported-ops :runtime-id (-> clj :state-ref deref :runtime-id)})
  (>!! tool-in {:op :request-tap-history
                :num 10
                :runtime-id (-> clj :state-ref deref :runtime-id)})


  (tap> {:tap 1})
  (>!! tool-in {:op :tap-subscribe :runtime-id (-> clj :state-ref deref :runtime-id)})
  (>!! tool-in {:op :tap-unsubscribe :runtime-id (-> clj :state-ref deref :runtime-id)})
  (tap> {:tap 1})

  (>!! tool-in {:op :tap-subscribe :runtime-id 7})

  (async/close! tool-in)
  (async/close! runtime-in)

  svc)