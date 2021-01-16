(ns shadow.remote.runtime.eval-support
  (:require
    [clojure.datafy :as d]
    [clojure.walk :as walk]
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.obj-support :as obj-support]
    ))

(def ^:dynamic get-ref nil)

(defn get-ref* [obj-support obj-id]
  (obj-support/get-ref obj-support obj-id))

;; wrap might not be all that useful
;; could just modify the initial code string but that changes source locations
;; so code is read first (preserving its metadata and source locations)
;; then wrap is applied (without preserving its metadata)
(defn apply-wrap [code wrap]
  (walk/prewalk-replace
    {'?CODE? code}
    wrap))

(comment
  (apply-wrap 123 `(identity ~'?CODE?)))

(defn clj-eval
  [{:keys [runtime obj-support]}
   {:keys [input] :as msg}]

  (let [{:keys [code ns wrap]
         :or {ns 'user}}
        input]
    (binding [*ns* (find-ns ns)
              get-ref #(get-ref* obj-support %)]
      (try
        (let [form
              (read-string code)

              eval-form
              (cond-> form
                wrap
                (apply-wrap (read-string wrap)))

              eval-start
              (System/currentTimeMillis)

              res
              (eval eval-form)

              eval-ms
              (- (System/currentTimeMillis) eval-start)

              ref-oid
              (obj-support/register obj-support res {:code code
                                                     :ns ns})]

          (shared/reply runtime msg
            {:op :eval-result-ref
             :eval-ms eval-ms
             :eval-ns (symbol (str *ns*))
             :ref-oid ref-oid}))

        (catch Exception e
          (let [ex-oid (obj-support/register obj-support e {:input input})]
            (shared/reply runtime msg
              {:op :eval-runtime-error
               :ex-oid ex-oid})))))))

(defn start [runtime obj-support]
  (let [svc
        {:runtime runtime
         :obj-support obj-support}]

    (p/add-extension runtime
      ::ext
      {:ops
       {:clj-eval #(clj-eval svc %)}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))
