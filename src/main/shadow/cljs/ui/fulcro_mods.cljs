(ns shadow.cljs.ui.fulcro-mods
  (:require-macros [shadow.cljs.ui.fulcro-mods])
  (:require
    [com.fulcrologic.fulcro.mutations :as fmut]
    [shadow.cljs.devtools.graph.util :as graph-util]))

(defonce tx-config-ref (atom {}))

(defn do-state-action [{:keys [state] :as env} client-action params]
  (swap! state client-action env params))

(defn do-remote [env remote-fn params]
  (remote-fn env params))

(defn handle-mutation* [{:keys [target ast] :as env}]
  (let [{tx-id :dispatch-key params :params} ast
        {:keys [action state-action remote remote-returning remotes refresh] :as tx-config}
        (get-in @tx-config-ref [tx-id])]

    ;; (js/console.log "handle-mutation" tx-id params env)

    (if-not tx-config
      (do (js/console.warn "Unknown app state mutation. Have you required the file with your mutations?" tx-id)
          nil)
      (-> {}
          (cond->
            (fn? remote)
            (assoc :remote #(do-remote % remote params))

            (true? remote)
            (assoc :remote (fn [env] true))

            remote-returning
            (assoc :remote #(fmut/returning % remote-returning))

            (and (nil? target) refresh)
            (assoc :refresh #(refresh % params))

            (and (nil? target) action)
            (assoc :action #(action env params))

            (and (nil? target) state-action)
            (assoc :action #(do-state-action env state-action params))
            )))))

(defn do-state-actions [fns state env params]
  (reduce
    (fn [state state-action]
      (state-action state env params))
    state
    fns))

;; defmutation has too much macro in it for my tastes
(defn handle-mutation [tx-def mdef]
  {:pre [(graph-util/tx-def? tx-def)]}
  (let [tx-id (:tx-id tx-def)]
    (cond
      ;; shortcut for functions that just want to update the state
      (fn? mdef)
      (swap! tx-config-ref assoc-in [tx-id :state-action] mdef)

      (vector? mdef)
      (swap! tx-config-ref assoc-in [tx-id :state-action] #(do-state-actions mdef %1 %2 %3))

      ;; more complex options passed in as map
      (map? mdef)
      (swap! tx-config-ref update tx-id merge mdef)

      :else
      (do (js/console.warn "illegal argument passed to add-mutation" mdef)
          (throw (ex-info "illegal argument passed to add-mutation" {}))))

    ;; fake multimethod dispatch since we keep our own dispatch
    (-add-method fmut/mutate tx-id handle-mutation*)
    ))

