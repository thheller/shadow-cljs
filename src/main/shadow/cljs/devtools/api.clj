(ns shadow.cljs.devtools.api
  (:refer-clojure :exclude (compile))
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.errors :as e]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.server.web.common :as web-common]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.cljs.repl :as repl]
            [shadow.repl :as r]
            [aleph.netty :as netty]
            [aleph.http :as aleph]
            [shadow.cljs.devtools.server.worker.ws :as ws]
            [clojure.string :as str]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.cljs.devtools.server.runtime :as runtime])
  (:import (java.io PushbackReader StringReader)
           (java.lang ProcessBuilder$Redirect)))

(defn get-worker
  [id]
  {:pre [(keyword? id)]}
  (let [{:keys [out supervisor] :as app}
        (runtime/get-instance!)]
    (super/get-worker supervisor id)
    ))

(defn get-or-start-worker [build-config opts]
  (let [{:keys [autobuild]}
        opts

        {:keys [out supervisor] :as app}
        (runtime/get-instance!)]

    (if-let [worker (super/get-worker supervisor (:id build-config))]
      worker
      (super/start-worker supervisor build-config)
      )))

(defn start-worker
  "starts a dev worker process for a given :build-id
  opts defaults to {:autobuild true}"
  ([build-id]
   (start-worker build-id {:autobuild true}))
  ([build-id opts]
   (let [{:keys [autobuild]}
         opts

         build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))]

     (-> (get-or-start-worker build-config opts)
         (cond->
           autobuild
           (-> (worker/start-autobuild)
               (worker/sync!)))))
   :started))

(defn active-builds []
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (super/active-builds supervisor)))

(defn compiler-env [build-id]
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (when-let [worker (super/get-worker supervisor build-id)]
      (-> worker
          :state-ref
          (deref)
          :compiler-state
          :compiler-env))))

(defn watch
  ([build-id]
   (start-worker build-id))
  ([build-id opts]
   (start-worker build-id opts)))

(comment
  (start-worker :browser)
  (stop-worker :browser))

(defn stop-worker [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]
    (super/stop-worker supervisor build-id)
    :stopped))

(defn node-repl
  ([]
   (node-repl {}))
  ([opts]
   (repl-impl/node-repl* (runtime/get-instance!) opts)))

(defn get-build-config [id]
  {:pre [(keyword? id)]}
  (config/get-build! id))

(defn dev*
  [build-config {:keys [autobuild] :as opts}]
  (let [config
        (config/load-cljs-edn)

        {:keys [out supervisor] :as app}
        (runtime/get-instance!)]

    (-> (get-or-start-worker build-config opts)
        (worker/watch out false)
        (worker/start-autobuild)
        (worker/sync!)
        (repl-impl/stdin-takeover! app))

    (super/stop-worker supervisor (:id build-config))
    :done))

(defn dev
  ([build]
   (dev build {:autobuild true}))
  ([build {:keys [autobuild] :as opts}]
   (try
     (let [build-config (config/get-build! build)]
       (dev* build-config opts))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn build-finish [{::comp/keys [build-info] :as state} config]
  (util/print-build-complete build-info config)
  state)

(defn compile* [build-config opts]
  (util/print-build-start build-config)
  (-> (comp/init :dev build-config)
      (comp/compile)
      (comp/flush)
      (build-finish build-config)))

(defn compile
  ([build]
   (compile build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (compile* build-config opts))
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn once
  "deprecated: use compile"
  ([build]
   (compile build {}))
  ([build opts]
   (compile build opts)))

(defn release*
  [build-config {:keys [debug source-maps pseudo-names] :as opts}]
  (util/print-build-start build-config)
  (-> (comp/init :release build-config)
      (cond->
        (or debug source-maps)
        (cljs/enable-source-maps)

        (or debug pseudo-names)
        (cljs/merge-compiler-options
          {:pretty-print true
           :pseudo-names true}))
      (comp/compile)
      (comp/optimize)
      (comp/flush)
      (build-finish build-config)))

(defn release
  ([build]
   (release build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (release* build-config opts))
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn check* [{:keys [id] :as build-config} opts]
  ;; FIXME: pretend release mode so targets don't need to account for extra mode
  ;; in most cases we want exactly :release but not sure that is true for everything?
  (-> (comp/init :release build-config)
      ;; using another dir because of source maps
      ;; not sure :release builds want to enable source maps by default
      ;; so running check on the release dir would cause a recompile which is annoying
      ;; but check errors are really useless without source maps
      (as-> X
        (-> X
            (assoc :cache-dir (io/file (:work-dir X) "shadow-cljs" (name id) "check"))
            ;; always override :output-dir since check output should never be used
            ;; only generates output for source maps anyways
            (assoc :output-dir (io/file (:work-dir X) "shadow-cljs" (name id) "check" "output"))))
      (cljs/enable-source-maps)
      (update-in [:compiler-options :closure-warnings] merge {:check-types :warning})
      (comp/compile)
      (comp/check))
  :done)

(defn check
  ([build]
   (check build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (check* build-config opts))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn repl [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)

        worker
        (super/get-worker supervisor build-id)]
    (if-not worker
      :no-worker
      (repl-impl/stdin-takeover! worker app))))

(defn help []
  (-> (slurp (io/resource "shadow/txt/repl-help.txt"))
      (println)))

(defn test-setup []
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (as-> X
        (cljs/merge-build-options X
          {:output-dir (io/file (:work-dir X) "shadow-test")}))
      (cljs/find-resources-in-classpath)
      ))

(defn node-execute! [node-args file]
  (let [script-args
        ["node"]

        pb
        (doto (ProcessBuilder. script-args)
          (.directory nil))]


    ;; not using this because we only get output once it is done
    ;; I prefer to see progress
    ;; (prn (apply shell/sh script-args))

    (let [node-proc (.start pb)]

      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getInputStream node-proc) *out*))))
      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getErrorStream node-proc) *err*))))

      (let [out (.getOutputStream node-proc)]
        (io/copy (io/file file) out)
        (.close out))

      ;; FIXME: what if this doesn't terminate?
      (let [exit-code (.waitFor node-proc)]
        exit-code))))

(defn test-all []
  (-> (comp/init :dev '{:id :shadow-cljs/test
                        :target :node-script
                        :main shadow.test-runner/main
                        :output-to "target/shadow-test-runner.js"
                        :hashbang false})
      (node/make-test-runner)
      (comp/compile)
      (comp/flush))

  (node-execute! [] "target/shadow-test-runner.js")
  ::test-all)

;; nREPL support

(def ^:dynamic *nrepl-cljs* nil)

(defn nrepl-select [id]
  (if-not (get-worker id)
    [:no-worker id]
    (do (set! *nrepl-cljs* id)
        ;; required for prompt?
        ;; don't actually need to do this
        (set! *ns* 'cljs.user)
        (println "To quit, type: :cljs/quit")
        [:selected id])))

(comment

  (defn test-affected
    [source-names]
    {:pre [(seq source-names)
           (not (string? source-names))
           (every? string? source-names)]}
    (-> (test-setup)
        (node/execute-affected-tests! source-names))
    ::test-affected))