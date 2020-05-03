(ns shadow.test.remote-inject
  (:require
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.api :as p]
    [shadow.test.env :as test-env]
    [cljs.test :as ct]))

#_#_#_(defn find-tests [svc msg]
  {:tests
   [{:id "uuid-or-just-keyword-or-sym"
     :ns some.ns
     :test-sym some.ns/foo.test
     :more :meta}]})

(defn run-test-by-id [svc {:keys [id]}]
  (send! {:test-start id})
  (run-test id)
  (send! {:test-complete id})
  )

(defn run-tests-by-id [svc {:keys [ids]}]
  (doseq [id ids]
    (run-test-by-id svc {:id id})))

(defmethod ct/report [::ui :pass] [m])
(defmethod ct/report [::ui :fail] [m])
(defmethod ct/report [::ui :error] [m])
(defmethod ct/report [::ui :summary] [m])
(defmethod ct/report [::ui :begin-test-ns] [m])
(defmethod ct/report [::ui :end-test-ns] [m])
(defmethod ct/report [::ui :begin-test-var] [m])
(defmethod ct/report [::ui :end-test-var] [m])
(defmethod ct/report [::ui :end-run-tests] [m])
(defmethod ct/report [::ui :end-test-all-vars] [m])
(defmethod ct/report [::ui :end-test-vars] [m])

(defn update-test-state [state {:keys [namespaces] :as test-data}]
  (reduce-kv
    (fn [state ns-sym ns-info]
      (let [known-state (get-in state [:namespaces ns-sym])

            fixtures-same?
            (and (= (get-in known-state [:fixtures :once])
                    (get-in ns-info [:fixtures :once]))
                 (= (get-in known-state [:fixtures :each])
                    (get-in ns-info [:fixtures :each])))

            ;; FIXME: ns reload will change all tests
            ;; REPL eval can change single tests but we have no trigger to get them
            ;; since it requires running the macro to gather the tests data again
            snapshot
            (->> (:vars ns-info)
                 ;; can't compare against the actual var instances
                 ;; since they are reconstructed on every compile
                 (reduce
                   (fn [snapshot test-var]
                     (let [{:keys [file line column name ns]} (meta test-var)]
                       (assoc snapshot name
                         {:file file
                          :line line
                          :column column
                          :name name
                          :ns ns
                          :test-fn @test-var})))
                   {}))

            tests-same?
            (= snapshot (:tests known-state))]

        (if (and fixtures-same? tests-same?)
          state
          ;; FIXME: shouldn't reset the entire known state for the ns if only one tests changed
          ;; assuming hot-reload only for now so that can't really happen
          (assoc-in state [:namespaces ns-sym] (assoc ns-info :tests snapshot)))))
    state
    namespaces))

(renv/init-extension! ::test-cljs #{:obj-support}
  (fn [{:keys [runtime obj-support] :as env}]
    (let [state-ref
          (atom {})

          svc
          {:runtime runtime
           :obj-support obj-support
           :state-ref state-ref}]

      (add-watch test-env/tests-ref ::watch
        (fn [_ _ old new]
          (swap! state-ref update-test-state new)
          ;; notify tools?
          ))

      ;; tests should be empty by the time this first runs but it might not
      ;; might happen if the websocket took time to connect and tests were populated in the meantime
      ;; or just this ns being hot-reloaded, make sure to initialize everything anyways
      (when (not= {} @test-env/tests-ref)
        (swap! state-ref update-test-state @test-env/tests-ref))

      ;; maybe just return the ops?
      ;; dunno if this extra layer is needed
      (p/add-extension runtime
        ::test-cljs
        {:ops
         {:test/find-tests (fn [msg] (js/console.log msg))}
         ;; :on-tool-disconnect #(tool-disconnect svc %)
         })
      svc))
  (fn [{:keys [runtime] :as svc}]
    (remove-watch test-env/tests-ref ::watch)
    (p/del-extension runtime ::test-cljs)))

