(ns shadow.build.targets.node-test
  (:refer-clojure :exclude (compile flush resolve))
  (:require
    [clojure.string :as str]
    [shadow.build :as build]
    [shadow.build.modules :as modules]
    [shadow.build.classpath :as cp]
    [shadow.build.targets.node-script :as node-script]
    [shadow.cljs.util :as util]
    [shadow.cljs.devtools.server.util :refer (pipe)]
    [shadow.build.async :as async]
    [shadow.build.test-util :as tu]))

(defn configure [{::build/keys [config mode] :as state}]
  (let [runner-ns (or (when-let [main (:main config)]
                        (-> main namespace symbol))
                      'shadow.test.node)]
    (-> state
        (assoc ::tu/runner-ns runner-ns)
        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "nodejs")
        (update :build-options merge {:greedy true
                                      :dynamic-resolve true})
        (update ::build/config merge {:main (get config :main 'shadow.test.node/main)})
        (update-in [::build/config :devtools] assoc :enabled false))))

;; since :configure is only called once in :dev
;; we delay setting the :entries until compile-prepare which is called every cycle
;; need to come up with a cleaner API for this
(defn test-resolve
  [{::build/keys [mode config] :as state}]
  (let [{:keys [ns-regexp] :or {ns-regexp "-test$"}}
        config

        test-namespaces
        (tu/find-namespaces-by-regexp state ns-regexp)

        entries
        (-> '[shadow.test.env] ;; must be included before any deftest because of the cljs.test mod
            (cond->
              (= :dev mode)
              (into (get-in config [:devtools :preloads])))
            (into test-namespaces)
            (conj (::tu/runner-ns state)))]

    (-> state
        (assoc ::tu/test-namespaces test-namespaces)
        (assoc-in [::modules/config :main :entries] entries)
        ;; re-analyze modules since we modified the entries
        (modules/analyze)
        (tu/inject-extra-requires)
        )))

(defn autorun-test [{::build/keys [config] :as state}]
  (util/with-logged-time
    [state {:type ::autorun}]
    (let [script-args
          ["node" (:output-to config)]

          proc
          (-> (ProcessBuilder. (into-array script-args))
              (.directory nil)
              (.start))]

      (println "========= Running Tests =======================")

      (.start (Thread. (bound-fn [] (pipe proc (.getInputStream proc) *out*))))
      (.start (Thread. (bound-fn [] (pipe proc (.getErrorStream proc) *err*))))

      ;; FIXME: what if this doesn't terminate?
      (let [exit-code (.waitFor proc)]
        (println "===============================================")
        (assoc state ::exit-code exit-code)))))

(defn process
  [{::build/keys [stage mode config] :as state}]
  (-> state
      (cond->
        (= stage :configure)
        (configure)

        (= stage :resolve)
        (test-resolve))

      (node-script/process)
      (cond->
        (and (= stage :flush)
             (:autorun config))
        (-> (async/wait-for-pending-tasks!)
            (autorun-test))
        )))