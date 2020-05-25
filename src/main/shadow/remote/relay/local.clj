(ns shadow.remote.relay.local
  (:require
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [shadow.remote.relay.api :as rapi]
    [shadow.jvm-log :as log]
    [clojure.set :as set])
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
  (>!! (:to origin) (maybe-add-mid msg {:op :unknown-relay-op :msg msg})))

(defn send-to-tools [state-ref msg]
  (doseq [{:keys [to]} (-> @state-ref :tools vals)]
    (>!! to msg)))

(defn send-to-runtimes [state-ref msg]
  (doseq [{:keys [to]} (-> @state-ref :runtimes vals)]
    (>!! to msg)))

(defn handle-runtime-msg
  [state-ref {:keys [rid] :as runtime} {msg-rid :rid :keys [tool-broadcast tid] :as msg}]
  (log/debug ::runtime-msg msg)
  (cond
    ;; runtime->tool
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

    ;; runtime->runtime
    ;; allow one runtime talking to another runtime directly?
    ;; FIXME: error out if msg contains own rid?
    (and msg-rid (not= rid msg-rid))
    (let [other-runtime (get-in @state-ref [:runtimes msg-rid])]
      (if-not other-runtime
        (>!! (:to runtime)
          (maybe-add-mid msg {:op :runtime-not-found :rid msg-rid}))
        (>!! (:to other-runtime)
          (-> msg
              (assoc :rid rid)))))

    tool-broadcast
    (send-to-tools state-ref (assoc msg :rid rid))

    :else
    (handle-sys-msg state-ref runtime msg)))

(defn handle-tool-msg
  [state-ref {:keys [tid] :as tool} {:keys [rid runtime-broadcast] :as msg}]
  ;; (log/debug ::tool-msg msg)
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


(defrecord LocalRelay [id-seq-ref state-ref]
  rapi/IToolRelay
  (tool-connect [relay from-tool tool-info]
    (let [tid (swap! id-seq-ref inc)

          to-tool
          (async/chan 10)

          tool-data
          {:tid tid
           :from from-tool
           :to to-tool
           :info tool-info}]

      (swap! state-ref assoc-in [:tools tid] tool-data)

      (async/thread
        (loop []
          (when-some [msg (<!! from-tool)]
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

  rapi/IRuntimeRelay
  (runtime-connect [relay from-runtime runtime-info]
    (let [rid (swap! id-seq-ref inc)

          to-runtime
          (async/chan 10)

          runtime-info
          (assoc runtime-info :since (Date.))

          runtime
          {:rid rid
           :runtime-info runtime-info
           :to to-runtime
           :from from-runtime}]

      (swap! state-ref assoc-in [:runtimes rid] runtime)

      (send-to-tools state-ref {:op :runtime-connect
                                :rid rid
                                :runtime-info runtime-info})

      (async/thread
        (loop []
          (when-some [msg (<!! from-runtime)]
            (handle-runtime-msg state-ref runtime msg)
            (recur)))

        (send-to-tools state-ref {:op :runtime-disconnect
                                  :rid rid})

        (swap! state-ref update :runtimes dissoc rid))

      (>!! to-runtime {:op :welcome
                       :rid rid})

      to-runtime
      )))

(defn start []
  (LocalRelay.
    (atom 0)
    (atom {:tools {}
           :runtimes {}})))

(defn stop [{:keys [state-ref] :as svc}]
  (let [{:keys [tools runtimes]} @state-ref]
    (doseq [{:keys [to]} (vals runtimes)]
      (async/close! to))
    (doseq [{:keys [to]} (vals tools)]
      (async/close! to))))

(defmethod handle-sys-msg :request-runtimes
  [state-ref tool msg]
  (let [{:keys [runtimes]} @state-ref
        result (->> runtimes
                    (vals)
                    (map #(select-keys % [:rid :runtime-info]))
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
  (def tool-out (rapi/tool-connect svc tool-in {}))

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
  (def runtime-out (rapi/runtime-connect svc runtime-in {}))

  (go (loop []
        (when-some [msg (<! runtime-out)]
          (prn :runtime-out)
          (clojure.pprint/pprint msg)
          (recur)))
      (prn :runtime-out-shutdown))

  (require '[shadow.remote.runtime.clj.local :as clj])

  (def clj (clj/start svc))
  (def clj (:clj-runtime (shadow.cljs.devtools.server.runtime/get-instance)))

  clj

  (clj/stop clj)

  (>!! tool-in {:op :request-runtimes})

  (def test-runtime-id 2)

  (>!! tool-in {:op :request-supported-ops :rid test-runtime-id})
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
  (async/close! runtime-out)

  svc)