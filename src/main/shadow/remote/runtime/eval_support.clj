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

  (let [{:keys [code ns wrap obj-refs]
         :or {ns 'user}}
        input]
    (binding [*ns* (find-ns ns)
              get-ref #(get-ref* obj-support %)]
      (try
        (let [form
              (read-string code)
              ;; FIXME: what if there are multiple forms in code?
              ;; I think client should ensure only one is sent?
              ;; currently UI just takes the whole string and does no parsing though

              eval-form
              (cond-> form
                wrap
                (apply-wrap (read-string wrap)))

              eval-start
              (System/currentTimeMillis)

              res
              (if-not obj-refs
                (eval form)
                (let [[a b c] obj-refs
                      eval-bindings
                      (-> {}
                          ;; FIXME: there has to be a better way for client to select which these should be
                          ;; the whole assumption is that this is NOT a streaming REPL
                          ;; but these are still useful for UI purposes
                          ;; introducing new things such as the $o via the apply-wrap hack work
                          ;; but are far from ideal
                          (cond->
                            a (assoc #'*1 (:obj (obj-support/get-ref obj-support a)))
                            b (assoc #'*2 (:obj (obj-support/get-ref obj-support b)))
                            c (assoc #'*3 (:obj (obj-support/get-ref obj-support c)))))]

                  (push-thread-bindings eval-bindings)
                  (try
                    (eval eval-form)
                    (finally
                      (pop-thread-bindings)))))

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

        (catch Throwable e
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
