(ns shadow.cljs.ui.fulcro-mods
  (:require-macros [shadow.cljs.ui.fulcro-mods])
  (:require
    [fulcro.client.mutations :as fmut]
    [shadow.cljs.devtools.graph.util :as graph-util]))

(defonce tx-config-ref (atom {}))

(defn init-remote-tx! [keys]
  {:pre [(every? symbol? keys)]}
  (swap! tx-config-ref
    (fn [tx-config]
      (reduce
        (fn [tx-config tx-id]
          ;; don't overwrite what we have configured previously
          (if (not= ::nothing (get-in tx-config [tx-id :remote] ::nothing))
            tx-config
            (assoc-in tx-config [tx-id :remote] true)))
        tx-config
        keys))))

(defn do-state-action [{:keys [state] :as env} client-action params]
  (swap! state client-action env params))

(defn do-remote [env remote-fn params]
  (remote-fn env params))

;; this is probably a bad idea, overriding the :default handler is never good
(defmethod fmut/mutate :default [{:keys [target] :as env} tx-id params]
  (let [{:keys [action state-action remote remote-returning] :as tx-config}
        (get-in @tx-config-ref [tx-id])]

    (if-not tx-config
      (do (js/console.warn "Unknown app state mutation. Have you required the file with your mutations?" tx-id)
          nil)
      (-> {}
          (cond->
            (fn? remote)
            (assoc :remote (do-remote env remote params))

            (true? remote)
            (assoc :remote true)

            remote-returning
            (assoc :remote (fmut/returning (:ast env) (:state env) remote-returning))

            (and (nil? target) action)
            (assoc :action #(action env params))

            (and (nil? target) state-action)
            (assoc :action #(do-state-action env state-action params))
            )))))

;; defmutation has too much macro in it for my tastes
(defn add-mutation [tx-def mdef]
  {:pre [(graph-util/tx-def? tx-def)]}
  (cond
    ;; shortcut for functions that just want to update the state
    (fn? mdef)
    (swap! tx-config-ref assoc-in [(:tx-id tx-def) :state-action] mdef)

    ;; more complex options passed in as map
    (map? mdef)
    (swap! tx-config-ref update (:tx-id tx-def) merge mdef)

    :else
    (do (js/console.warn "illegal argument passed to add-mutation" mdef)
        (throw (ex-info "illegal argument passed to add-mutation" {})))))

