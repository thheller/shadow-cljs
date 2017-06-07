(ns shadow.cljs.devtools.remote.client
  (:require-macros [cljs.core.async.macros :refer (go)])
  ;; cursive is driving me crazy when I try to use net alias
  (:require ["net" :as node-net]
            [cljs.core.async :as async]
            [shadow.cljs.devtools.remote.protocol :as protocol]
            ))

(defn connect [{:keys [host port in out] :as config}]
  (let [tcp-in
        (async/chan 10)

        client
        (node-net/connect port host)

        connect
        (async/chan)

        write-fn
        (fn [out-txt]
          (when-not (.write client out-txt)
            ;; FIXME: wait for drain?
            (prn [:failed-to-write-fully])
            ))]

    ;; just moves data onto tcp-in
    (.on client "data" #(async/offer! tcp-in (.toString %1)))
    (.on client "end" #(async/close! tcp-in))

    ;; read-loop transfers tcp-in -> in
    ;; write-loop transfers out -> write-fn
    (.on client "connect"
      (fn [err]
        (if err
          (do (async/close! in)
              (async/close! out)
              (async/close! tcp-in))

          (do (async/put! connect true)
              (protocol/read-loop tcp-in in)
              (go (<! (protocol/write-loop out write-fn))
                  (.close client))))

        (async/close! connect)
        ))

    connect
    ))



