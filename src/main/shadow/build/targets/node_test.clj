(ns shadow.build.targets.node-test
  (:refer-clojure :exclude (compile flush resolve))
  (:require [clojure.string :as str]
            [shadow.build :as build]
            [shadow.build.modules :as modules]
            [shadow.build.classpath :as cp]
            [shadow.build.targets.node-script :as node-script]
            [shadow.cljs.util :as util]
            [shadow.cljs.devtools.server.util :refer (pipe)]
            [hiccup.page :refer (html5)]
            [clojure.java.io :as io]
            [cljs.compiler :as cljs-comp]
            [shadow.build.api :as build-api]
            [shadow.build.output :as output]
            [shadow.build.data :as data])
  (:import [java.lang ProcessBuilder$Redirect]))

(defn configure [{::build/keys [config mode] :as state}]
  (-> state
      (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "nodejs")
      (update :build-options merge {:greedy true
                                    :dynamic-resolve true})
      ;; FIXME: allow custom :runner-ns?
      (update ::build/config merge {:main 'shadow.test.node/main})
      (update-in [::build/config :devtools] assoc :enabled false)))

;; since :configure is only called once in :dev
;; we delay setting the :entries until compile-prepare which is called every cycle
;; need to come up with a cleaner API for this
(defn test-resolve
  [{:keys [classpath] ::build/keys [mode config] :as state}]
  (let [{:keys [ns-regexp] :or {ns-regexp "-test$"}}
        config

        test-namespaces
        (->> (cp/get-all-resources classpath)
             (filter :file) ;; only test with files, ie. not tests in jars.
             (filter #(= :cljs (:type %)))
             (map :ns)
             (filter (fn [ns]
                       (re-find (re-pattern ns-regexp) (str ns))))
             (into []))

        entries
        (-> '[shadow.test.env] ;; must be included before any deftest because of the cljs.test mod
            (into test-namespaces)
            (conj 'shadow.test.node))]

    (-> state
        (assoc-in [::modules/config :main :entries] entries)
        ;; re-analyze modules since we modified the entries
        (modules/analyze))))

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
        (autorun-test)
        )))