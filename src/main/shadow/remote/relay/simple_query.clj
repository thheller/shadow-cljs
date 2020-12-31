(ns shadow.remote.relay.simple-query
  (:require [clojure.spec.alpha :as s]))

;; extremely simplistic query engine
;; taking a hiccup-ish edn structure that is tested against a single obj
;; never evals, all predicates are keywords and implemented via multi-method

(s/def ::query
  (s/and
    vector?
    (s/cat
      :pred
      keyword?
      :pred-args
      (s/+ any?))))

;; FIXME: multi-spec this so each conditional can supply specs

(comment
  ;; not actually valid, needs multi-spec
  (s/explain ::query [:and 1 2 3])
  ;; valid
  (s/explain ::query [:and [:eq :foo 1]]))

(defn query-lookup [obj path]
  (if (vector? path)
    (get-in obj path)
    (get obj path)))

(defmulti query-condition (fn [env obj pred] (first pred)))

;; [:eq :some-attr "some-val"]
(defmethod query-condition :eq [env obj [_ attr value]]
  (let [attr-value (query-lookup obj attr)]
    (= attr-value value)))

(defmethod query-condition := [env obj [_ attr value]]
  (let [attr-value (query-lookup obj attr)]
    (= attr-value value)))

(defmethod query-condition :not [env obj [_ pred]]
  (not (query-condition env obj pred)))

(defmethod query-condition :nil? [env obj [_ attr]]
  (let [attr-value (query-lookup obj attr)]
    (nil? attr-value)))

(defmethod query-condition :some? [env obj [_ attr]]
  (let [attr-value (query-lookup obj attr)]
    (some? attr-value)))

;; [:contains :some-set :some-val]
(defmethod query-condition :contains [env obj [_ attr value]]
  (let [attr-value (query-lookup obj attr)]
    (contains? attr-value value)
    ))

;; [:contained-in :some-attr :some-set]
(defmethod query-condition :contained-in [env obj [_ attr set]]
  (let [attr-value (query-lookup obj attr)]
    (contains? set attr-value)
    ))

(defmethod query-condition :or [env obj [_ & preds]]
  (reduce
    (fn [result test]
      (if (query-condition env obj test)
        (reduced true)
        result))
    false
    preds))

(defmethod query-condition :and [env obj [_ & preds]]
  (reduce
    (fn [result test]
      (if-not (query-condition env obj test)
        (reduced false)
        result))
    true
    preds))

(defn query [env obj pred]
  {:pre [(map? env)
         (map? obj)]}
  (if (or (nil? pred) (empty? pred))
    true
    (query-condition env obj pred)))

(comment
  (query
    {:env true}
    {:worker true
     :build-id :browser}
    [:and
     [:eq :worker true]
     [:eq :build-id :browser]]))
