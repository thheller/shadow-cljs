(ns shadow.remote.relay
  (:require
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [shadow.jvm-log :as log])
  (:import [java.util Date]))

(defn maybe-add-mid [{:keys [mid] :as req} res]
  (cond-> res
    mid
    (assoc :mid mid)))

(defmulti handle-sys-msg
  ;; origin is either a runtime or a tool
  (fn [state-ref origin msg] (:op msg ::default))
  :default ::default)

(defmethod handle-sys-msg ::default
  [state-ref origin {:keys [op] :as msg}]
  (>!! (:to origin) (maybe-add-mid msg {:op :unknown-op
                                           :request-op op})))

(defn send-to-tools [state-ref msg]
  (doseq [{:keys [to]} (-> @state-ref :tools vals)]
    (>!! to msg)))

(defn send-to-runtimes [state-ref msg]
  (doseq [{:keys [to]} (-> @state-ref :runtimes vals)]
    (>!! to msg)))

(defn handle-runtime-msg
  [state-ref {:keys [rid] :as runtime} {:keys [tool-broadcast tid] :as msg}]
  (log/debug ::runtime-msg msg)
  (cond
    ;; only send to specific tool
    tid
    (let [tool (get-in @state-ref [:tools tid])]
      (if-not tool
        (>!! (:to runtime)
             (maybe-add-mid msg {:op :tool-not-found :tid tid}))
        (>!! (:to tool)
             (-> msg
                 (dissoc :tid)
                 (assoc :rid rid)))))

    tool-broadcast
    (send-to-tools state-ref (assoc msg :rid rid))

    :else
    (handle-sys-msg state-ref runtime msg)))

(defn handle-tool-msg
  [state-ref {:keys [tid] :as tool} {:keys [rid runtime-broadcast] :as msg}]
  (log/debug ::tool-msg msg)
  (cond
    ;; client did send :rid, forward to runtime if found
    rid
    (let [runtime (get-in @state-ref [:runtimes rid])]
      (if-not runtime
        (>!! (:to tool)
             (maybe-add-mid msg {:op :runtime-not-found
                                    :rid rid}))

        ;; forward with tid only, replies should be coming from runtime-out
        (>!! (:to runtime)
             (-> msg
                 (assoc :tid tid)
                 (dissoc :rid)))))

    ;; FIXME: broadcast may not be a good idea, tools or runtimes can always iterate themselves
    runtime-broadcast
    (send-to-runtimes state-ref (assoc msg :tid tid))

    ;; treat as system op
    :else
    (handle-sys-msg state-ref tool msg)))

(defn runtime-connect [svc from-runtime runtime-info]
  (let [{:keys [state-ref id-seq-ref]} svc

        rid (swap! id-seq-ref inc)

        to-runtime
        (async/chan 10)

        runtime-info
        (assoc runtime-info :since (Date.) :rid rid)

        runtime
        {:rid rid
         :runtime-info runtime-info
         :to to-runtime
         :from from-runtime}]

    (swap! state-ref assoc-in [:runtimes rid] runtime)

    (send-to-tools state-ref {:op :runtime-connect
                              :rid rid
                              :runtime-info runtime-info})

    (go (loop []
          (when-some [msg (<! from-runtime)]
            (handle-runtime-msg state-ref runtime msg)
            (recur)))

        (send-to-tools state-ref {:op :runtime-disconnect
                                  :rid rid})

        (swap! state-ref update :runtimes dissoc rid))

    (>!! to-runtime {:op :welcome
                     :rid rid})

    to-runtime
    ))

(defn tool-connect
  [svc from-tool]
  (let [{:keys [state-ref id-seq-ref]} svc

        tid (swap! id-seq-ref inc)

        to-tool
        (async/chan 10)

        tool-data
        {:tid tid
         :from from-tool
         :to to-tool}]

    (swap! state-ref assoc-in [:tools tid] tool-data)

    (go (loop []
          (when-some [msg (<! from-tool)]
            (handle-tool-msg state-ref tool-data msg)
            (recur)))

        ;; send to all runtimes so they can cleanup state?
        (send-to-runtimes state-ref {:op :tool-disconnect
                                     :tid tid})

        (swap! state-ref update :tools dissoc tid)
        (async/close! to-tool))

    (>!! to-tool {:op :welcome
                  :tid tid})

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
         (maybe-add-mid msg {:op :runtimes
                                :runtimes result}))))

(comment
  (def svc (start))
  (def svc (:relay (shadow.cljs.devtools.server.runtime/get-instance)))

  svc

  (stop svc)

  (def tool-in (async/chan))
  (def tool-out (tool-connect svc tool-in))

  (def tid-ref (atom nil))

  (go (loop []
        (when-some [msg (<! tool-out)]
          (prn :tool-out)
          (clojure.pprint/pprint msg)
          (when (= :welcome (:op :msg))
            (reset! tid-ref (:tid msg)))
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

  (>!! tool-in {:op :request-supported-ops :rid (-> clj :state-ref deref :rid)})
  (>!! tool-in {:op :request-tap-history
                :num 10
                :rid (-> clj :state-ref deref :rid)})


  (tap> {:tap 1})
  (>!! tool-in {:op :tap-subscribe :rid (-> clj :state-ref deref :rid)})
  (>!! tool-in {:op :tap-unsubscribe :rid (-> clj :state-ref deref :rid)})
  (tap> {:tap 1})

  (>!! tool-in {:op :tap-subscribe :rid 7})

  (async/close! tool-in)
  (async/close! runtime-in)

  svc)