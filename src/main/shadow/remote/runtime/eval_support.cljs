(ns shadow.remote.runtime.eval-support
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.obj-support :as obj-support]))

;; FIXME: can I get this generic enough to also function with regular CLJS?

(defn eval-cljs
  [{:keys [runtime] :as svc} msg]
  (p/reply runtime msg
    {:op :eval-result
     :result ::not-yet}))

(defn eval-js
  [{:keys [runtime obj-support] :as svc} {:keys [code] :as msg}]

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