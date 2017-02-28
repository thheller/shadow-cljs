(ns shadow.devtools.server.compiler.custom
  (:require [shadow.devtools.server.compiler :as comp]))

(defn get-target-fn [config]
  (let [target-fn (:target-fn config)
        target-ns (-> target-fn namespace symbol)]

    (when-not (find-ns target-ns)
      (try
        (require target-ns)
        (catch Exception e
          (throw (ex-info "failed to require target-fn" config e)))))

    (let [fn (ns-resolve target-ns target-fn)]
      (when-not fn
        (throw (ex-info (str "custom target-fn " target-fn " not found") config)))

      fn
      )))

(defmethod comp/process :custom
  [{::comp/keys [stage config] :as state}]
  (let [{::keys [target-fn] :as state}
        (if (= :init stage)
          (let [target-fn (get-target-fn config)]
            (assoc state ::target-fn target-fn))
          state)]

    (target-fn state)))
