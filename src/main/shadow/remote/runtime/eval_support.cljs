(ns shadow.remote.runtime.eval-support
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.cljs.env :as renv]
    ))

(def ^:dynamic obj-support-inst nil)

(defn get-ref [oid]
  (when-not obj-support-inst
    (throw (ex-info "obj-support not bound, can only call this from eval" {:oid oid})))
  (obj-support/get-ref obj-support-inst oid))

(defn eval-cljs
  [{:keys [^Runtime runtime obj-support] :as svc} {:keys [input] :as msg}]
  ;; can't use binding because this has to go async
  ;; required for $o in the UI to work, would be good to have a cleaner API for this
  (set! obj-support-inst obj-support)
  (renv/eval-cljs runtime input
    ;; {:code "1 2 3"} would trigger 3 results
    (fn [{:keys [result] :as info}]
      (set! obj-support-inst nil) ;; cleanup

      ;; (js/console.log "eval-cljs" info msg)

      (case result
        :compile-error
        (let [{:keys [ex-rid ex-oid]} info]
          (shared/reply runtime msg
            {:op :eval-compile-error
             :ex-rid ex-rid
             :ex-oid ex-oid}))

        :runtime-error
        (let [{:keys [ex]} info
              ex-oid (obj-support/register obj-support ex {:msg input})]
          (shared/reply runtime msg
            {:op :eval-runtime-error
             :ex-oid ex-oid}))

        :ok
        (let [{:keys [results warnings]} info
              val
              (if (= 1 (count results))
                (first results)
                results)]
          ;; pretending to be one result always
          ;; don't want to send multiple results in case code contained multiple forms
          (let [ref-oid (obj-support/register obj-support val {:msg input})]
            (shared/reply runtime msg
              {:op :eval-result-ref
               :ref-oid ref-oid
               :warnings warnings})))

        (js/console.error "Unhandled eval-cljs result" info)))))

(defn eval-js
  [{:keys [^Runtime runtime obj-support] :as svc} {:keys [code] :as msg}]

  (try
    (let [res (renv/eval-js runtime code)
          ref-oid (obj-support/register obj-support res {:js-code code})]

      (shared/reply runtime msg
        ;; FIXME: separate result ops for :eval-cljs :eval-js :eval-clj?
        {:op :eval-result-ref
         :ref-oid ref-oid}))

    (catch :default e
      (shared/reply runtime msg
        {:op :eval-error
         :e (.-message e)}))))

(defn start [runtime obj-support]
  (let [svc
        {:runtime runtime
         :obj-support obj-support}]

    (shared/add-extension runtime
      ::ext
      {:ops
       {:eval-js #(eval-js svc %)
        :eval-cljs #(eval-cljs svc %)}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))