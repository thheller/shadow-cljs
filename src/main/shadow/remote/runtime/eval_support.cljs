(ns shadow.remote.runtime.eval-support
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.obj-support :as obj-support]
    ))

(def ^:dynamic obj-support-inst nil)

(defn get-ref [oid]
  (when-not obj-support-inst
    (throw (ex-info "obj-support not bound, can only call this from eval" {:oid oid})))
  (obj-support/get-ref obj-support-inst oid))

(defn cljs-eval
  [{:keys [^Runtime runtime obj-support] :as svc} {:keys [input] :as msg}]
  ;; can't use binding because this has to go async
  ;; required for $o in the UI to work, would be good to have a cleaner API for this
  (set! obj-support-inst obj-support)
  (p/cljs-eval runtime input
    ;; {:code "1 2 3"} would trigger 3 results
    (fn [{:keys [result] :as info}]
      (set! obj-support-inst nil) ;; cleanup

      ;; (js/console.log "cljs-eval" info msg)

      (case result
        :compile-error
        (let [{:keys [ex-rid ex-oid report]} info]
          (shared/reply runtime msg
            {:op :eval-compile-error
             :ex-rid ex-rid
             :ex-oid ex-oid
             :report report}))

        :runtime-error
        (let [{:keys [ex]} info
              ex-oid (obj-support/register obj-support ex {:msg input})]
          (shared/reply runtime msg
            {:op :eval-runtime-error
             :ex-oid ex-oid}))

        :warnings
        (let [{:keys [warnings]} info]
          (shared/reply runtime msg
            {:op :eval-compile-warnings
             :warnings warnings}))

        :ok
        (let [{:keys [results warnings time-start time-finish]} info
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
               :eval-ms (- time-finish time-start)
               :eval-ns (:ns info)
               :warnings warnings})))

        (js/console.error "Unhandled cljs-eval result" info)))))

(defn js-eval
  [{:keys [^Runtime runtime obj-support] :as svc} {:keys [code] :as msg}]

  (try
    (let [res (p/js-eval runtime code)
          ref-oid (obj-support/register obj-support res {:js-code code})]

      (shared/reply runtime msg
        ;; FIXME: separate result ops for :cljs-eval :js-eval :clj-eval?
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
       {:js-eval #(js-eval svc %)
        :cljs-eval #(cljs-eval svc %)}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))