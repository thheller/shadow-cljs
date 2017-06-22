(ns shadow.cljs.devtools.server.repl-impl
  (:require [clojure.core.async :as async :refer (go <! >! >!! <!! alt!!)]
            [clojure.java.io :as io]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.repl :as r]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.supervisor :as super])
  (:import (java.io StringReader PushbackReader)))

(defn print-result [result]
  (locking cljs/stdout-lock
    (case (:type result)
      :repl/result
      (println (:value result))

      :repl/set-ns-complete
      nil

      :repl/require-complete
      nil

      :repl/interrupt
      nil

      :repl/timeout
      (println "Timeout while waiting for REPL result.")

      :repl/no-eval-target
      (println "There is no connected JS runtime.")

      :repl/too-many-eval-clients
      (println "There are too many connected processes.")

      (prn [:result result]))
    (flush)))

(defn worker-repl-state [worker]
  (-> worker :state-ref deref :compiler-state :repl-state))

(defn worker-read-string [worker s]
  (let [rdr
        (-> s
            (StringReader.)
            (PushbackReader.))

        repl-state
        (worker-repl-state worker)]

    (repl/read-one repl-state rdr {})))

(defn repl-level [worker]
  {::r/lang :cljs
   ::r/get-current-ns
   #(:current (worker-repl-state worker))

   ::r/read-string
   #(worker-read-string worker %)
   })

(defn debug [app action data]
  (when (-> app :config :debug)
    (>!! (:out app) {:type :debug :action action :data data})))

(def repl-api-fns
  ;; return value of these is ignored, print to *out* should work?
  {})

(defn do-repl-api-fn [app worker repl-state {:keys [form] :as read-result}]
  (let [[special-fn & args]
        form

        handler
        (get repl-api-fns special-fn)]

    (apply handler app worker repl-state read-result args)))

(defn stdin-takeover!
  [worker {:keys [out] :as app}]
  (r/takeover (repl-level worker)
    (loop []
      ;; unlock stdin when we can't get repl-state, just in case
      (when-let [repl-state (worker-repl-state worker)]

        ;; FIXME: inf-clojure checks when there is a space between \n and =>
        (print (format "[%d:%d]~%s=> " r/*root-id* r/*level-id* (-> repl-state :current :ns)))
        (flush)

        ;; need the repl state to properly support reading ::alias/foo
        (let [{:keys [eof? form] :as read-result}
              (repl/read-one repl-state *in* {})]

          (debug app :stdin-takeover! {:read-result read-result})

          (cond
            eof?
            :eof

            (nil? form)
            (recur)

            (= :repl/quit form)
            :quit

            (= :cljs/quit form)
            :quit

            (and (list? form)
                 (contains? repl-api-fns (first form)))
            (do (do-repl-api-fn app worker repl-state read-result)
                (recur))

            :else
            (when-some [result (worker/repl-eval worker ::stdin read-result)]
              (print-result result)
              (when (not= :repl/interrupt (:type result))
                (recur)))))))))

(defn node-repl*
  [{:keys [supervisor] :as app}
   {:keys [verbose
           node-args
           node-command
           pwd]
    :or {node-args []
         node-command "node"}}]
  (let [script-name
        "target/shadow-node-repl.js"

        build-config
        {:id :node-repl
         :target :node-script
         :main 'shadow.cljs.devtools.client.node-repl/main
         :hashbang false
         :output-to script-name}

        #_[out-chan
           (-> (async/sliding-buffer 10)
               (async/chan))

           _
           (go (loop []
                 (when-some [msg (<! out-chan)]
                   (try
                     (util/print-worker-out msg verbose)
                     (catch Exception e
                       (prn [:print-worker-out-error e])))
                   (recur)
                   )))]

        ;; FIXME: just connect if already running
        worker
        (super/start-worker supervisor build-config)

        result
        (-> worker
            ;; forwards all build messages to the server output
            ;; prevents spamming the REPL with build progress
            (worker/watch (:out app) false)
            ;; warnings currently go to the output of the server
            ;; should probably go to the REPL as well
            ;; (worker/watch out-chan)
            (worker/compile!))]

    ;; FIXME: validate that compilation succeeded

    (let [node-script
          (doto (io/file script-name)
            ;; just to ensure it is removed, should this crash for some reason
            (.deleteOnExit))

          node-proc
          (-> (ProcessBuilder.
                (into-array
                  (into [node-command] node-args)))
              (.directory
                ;; nil defaults to JVM working dir
                (when pwd
                  (io/file pwd)))
              (.start))]

      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getInputStream node-proc) *out*))))
      (.start (Thread. (bound-fn [] (util/pipe node-proc (.getErrorStream node-proc) *err*))))

      ;; FIXME: validate that proc started

      (let [stdin-fn
            (bound-fn []
              (stdin-takeover! worker app))

            stdin-thread
            (doto (Thread. stdin-fn)
              (.start))]

        ;; async wait for the node process to exit
        ;; in case it crashes
        (async/thread
          (try
            (.waitFor node-proc)

            ;; process crashed, try to interrupt stdin block
            ;; wont' work if it is reading off *in* but we can try
            (when (.isAlive stdin-thread)
              (.interrupt stdin-thread))

            (catch Exception e
              (prn [:node-wait-error e]))))

        ;; piping the script into node-proc instead of using command line arg
        ;; as node will otherwise adopt the path of the script as the require reference point
        ;; we want to control that via pwd
        (let [out (.getOutputStream node-proc)]
          (io/copy (slurp node-script) out)
          (.close out))

        (.join stdin-thread)

        ;; FIXME: more graceful shutdown of the node-proc?
        (when (.isAlive node-proc)
          (.destroy node-proc)
          (.waitFor node-proc))

        (when (.exists node-script)
          (.delete node-script)))
      ))

  (super/stop-worker supervisor :node-repl)

  #_(locking cljs/stdout-lock
      (println "Node REPL shutdown. Goodbye ..."))

  :repl/quit)