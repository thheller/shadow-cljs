(ns shadow.remote.runtime.eval-support
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.obj-support :as obj-support]))

(def ^:dynamic obj-support-inst nil)

(defn get-ref [oid]
  (when-not obj-support-inst
    (throw (ex-info "obj-support not bound, can only call this from eval" {:oid oid})))
  (obj-support/get-ref obj-support-inst oid))

(defn eval-cljs
  [{:keys [^Runtime runtime obj-support] :as svc} msg]
  ;; can't use binding because this has to go async
  (set! obj-support-inst obj-support)
  (.eval-cljs runtime msg
    ;; FIXME: do we allow multiple actions per msg?
    ;; {:code "1 2 3"} would trigger 3 results
    (fn [{:keys [eval-results] :as result}]
      (set! obj-support-inst nil) ;; cleanup
      (js/console.log "eval-cljs result" result)
      (doseq [{:keys [value]} eval-results]
        (if (nil? value)
          (p/reply runtime msg
            {:op :eval-result
             :result nil})
          (let [ref-oid (obj-support/register obj-support value {:msg msg})]
            (p/reply runtime msg
              {:op :eval-result-ref
               :ref-oid ref-oid})))))))

(defn eval-js
  [{:keys [^Runtime runtime obj-support] :as svc} {:keys [code] :as msg}]

  (try
    ;; FIXME: protocol-ize? eval semantics are different in node/browser
    (let [res (.eval-js runtime code)
          ref-oid (obj-support/register obj-support res {:js-code code})]

      (p/reply runtime msg
        ;; FIXME: separate result ops for :eval-cljs :eval-js :eval-clj?
        {:op :eval-result-ref
         :ref-oid ref-oid}))

    (catch :default e
      (p/reply runtime msg
        {:op :eval-error
         :e (.-message e)}))))

(defn start [runtime obj-support]
  (let [svc
        {:runtime runtime
         :obj-support obj-support}]

    (p/add-extension runtime
      ::ext
      {:ops
       {:eval-js #(eval-js svc %)
        :eval-cljs #(eval-cljs svc %)}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))