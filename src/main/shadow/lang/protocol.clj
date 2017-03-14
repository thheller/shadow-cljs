(ns shadow.lang.protocol
  (:require [clojure.core.async :as async :refer (>!!)]))

(defn async-ok [result]
  {::result-type :ok
   :result result})

(defn async-error [error-code error-msg]
  {::result-type :error
   :error-code error-code
   :error-msg error-msg})

(defn call-ok [next-state result]
  (assoc (async-ok result) :next-state next-state))

(defn call-error [next-state error-code error-msg]
  (assoc (async-error error-code error-msg) :next-state next-state))

(defn call-defer [next-state async-chan]
  {::result-type :defer
   :chan async-chan})

(defn notify! [{:keys [notify-chan] :as next-state} notify-method notify-params]
  (>!! notify-chan {"method" notify-method "params" notify-params})
  next-state)

(defmulti handle-call
  (fn [client-state method params]
    method)
  :default ::default)

(defmethod handle-call ::default [client-state method params]
  (prn [::unsupported method])
  ;; https://github.com/Microsoft/language-server-protocol/blob/master/protocol.md
  ;; MethodNotFound: number = -32601;
  (call-error client-state -32601 (format "%s not supported" method)))

(defmulti handle-cast
  (fn [client-state method params]
    method)
  :default ::default)

(defmethod handle-cast ::default
  [client-state method params]
  (prn [::dropped-cast method])
  client-state)

(defn client-reset [client-state]
  (assoc client-state
    :pending {}
    :cancelled #{}
    :open-files #{}
    :initialized? false))

(defmethod handle-cast "initialized"
  [client-state _ params]
  (assoc client-state :initialized? true))

;; FIXME: protocol says this should completely terminate the server
;; but so far it doesn't launch this in the first place, so just ignore it
(defmethod handle-call "shutdown"
  [client-state method params]
  (-> client-state
      (client-reset)
      (call-ok nil)))

(defmethod handle-cast "exit"
  [client-state _ params]
  (client-reset client-state))
