(ns shadow.devtools.cli
  (:require [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.core.async :as async :refer (go <! >! >!! <!!)]
            [shadow.cljs.node :as node]
            [shadow.cljs.umd :as umd]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.build :as cljs]
            [shadow.devtools.server.system :as sys]
            [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.config :as config]

            [shadow.cljs.log :as cljs-log])
  (:import (java.lang ProcessBuilder$Redirect)))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries depends-on module-config))
    state
    modules))

(defn- configure-browser-build [state]
  (let [config (::build state)
        {:keys [public-path public-dir modules] :as config}
        (merge default-browser-config config)]
    (-> state
        (cond->
          public-dir
          (cljs/set-build-options
            {:public-dir (io/file public-dir)})
          public-path
          (cljs/set-build-options
            {:public-path public-path}))
        (configure-modules modules)
        )))

(defn- configure-script-build [state]
  (let [{:keys [output-to main] :as config} (::build state)]
    (-> state
        (node/configure config))))

(defn- configure-library-build [state]
  (let [{:keys [exports] :as config} (::build state)]
    (-> state
        (umd/create-module exports config)
        )))

;; FIXME: spec for cli
(defn- pick-build [config [build-id & more :as args]]
  (if (nil? build-id)
    (first config)
    (let [id (keyword build-id)
          build
          (->> config
               (filter #(= id (:id %)))
               (first))]
      (when-not build
        (throw (ex-info (str "no build with id: " build-id) {:id id})))
      build
      )))

(defn- dev-setup [{:keys [dev] :as build}]
  (-> (cljs/init-state)
      (cljs/set-build-options {:use-file-min false})
      (cljs/enable-source-maps)
      (cond->
        dev
        (cljs/set-build-options dev))
      (cljs/find-resources-in-classpath)
      (assoc ::build build)
      ))

(defn once [& args]
  (let [config (config/load-cljs-edn!)
        build (pick-build config args)
        state (dev-setup build)]
    (case (:target build)
      :browser
      (-> state
          (configure-browser-build)
          (cljs/compile-modules)
          (cljs/flush-unoptimized))

      :script
      (-> state
          (configure-script-build)
          (node/compile)
          (node/flush-unoptimized))

      :library
      (-> state
          (configure-library-build)
          (cljs/compile-modules)
          (umd/flush-unoptimized-module)
          )))
  :done)

(def default-devtools-options
  {:console-support true})

(defn stdin-takeover! [runtime-ref build-proc]

  (let [repl-in
        (async/chan 1)

        repl-result
        (build/repl-client-connect build-proc ::stdin repl-in)

        ;; FIXME: how to display results properly?
        _ (go (loop []
                (when-some [result (<! repl-result)]
                  (println result)
                  (recur))))

        loop-result
        (loop []
          (let [repl-state
                (build/repl-state build-proc)

                {:keys [eof? form source] :as read-result}
                (repl/read-stream! repl-state (. System -in))]

            (cond
              eof?
              :eof

              (nil? form)
              (recur)

              (= :cljs/quit form)
              :quit

              :else
              (do (>!! repl-in read-result)
                  (recur))
              )))]

    (async/close! repl-in)

    loop-result
    ))

(defonce log-lock (Object.))

(defn stdout-dump []
  (let [chan
        (-> (async/sliding-buffer 10)
            (async/chan))]

    (async/go
      (loop []
        (when-some [x (<! chan)]
          (locking log-lock
            (case (:type x)
              :build-log
              (println (cljs-log/event->str (:event x)))

              :build-start
              (println "Build started.")

              :build-success
              (do (println "Build completed.")
                  (doseq [w (-> x :info :warnings)]
                    (prn w)))

              ;; default
              (prn [:log x])))
          (recur)
          )))

    chan
    ))



(defn- run-dev [build-config]
  (let [runtime-ref (sys/start-cli)]
    (try
      (let [{:keys [build] :as app}
            (:app @runtime-ref)

            proc
            (-> (build/proc-start build)
                (build/configure build-config)
                (build/start-autobuild)
                (build/watch (stdout-dump)))]

        (stdin-takeover! runtime-ref proc))

      (finally
        (sys/shutdown-system @runtime-ref))))
  )

(defn dev [& args]
  (let [config
        (config/load-cljs-edn!)

        build-config
        (pick-build config args)]

    (run-dev build-config)))

(defn node-repl
  ([]
   (node-repl {}))
  ([{:keys [node-args
            node-command
            pwd]
     :or {node-args []
          node-command "node"}}]
   (let [runtime-ref (sys/start-cli)]
     (try
       (let [{:keys [build] :as app}
             (:app @runtime-ref)

             script-name
             "target/shadow-node-repl.js"

             build-config
             {:id :node-repl
              :target :script
              :main 'shadow.devtools.client.node-repl/main
              :output-to script-name}

             proc
             (-> (build/proc-start build)
                 (build/watch (stdout-dump))
                 (build/configure build-config))]

         (let [result
               (build/compile! proc)

               node-script
               (doto (io/file script-name)
                 ;; just to ensure it is removed, should this crash for some reason
                 (.deleteOnExit))

               node-proc
               (-> (ProcessBuilder.
                     (into-array
                       (into [node-command] node-args)))
                   (.redirectOutput ProcessBuilder$Redirect/INHERIT)
                   (.redirectError ProcessBuilder$Redirect/INHERIT)
                   (.directory
                     ;; nil defaults to JVM working dir
                     (when pwd
                       (io/file pwd)))
                   (.start))]

           (let [out (.getOutputStream node-proc)]
             ;; piping the script into node-proc instead of using command line arg
             ;; as node will otherwise adopt the path of the script as the require reference point
             ;; we want to control that via pwd
             (io/copy (slurp node-script) out)
             (.close out))

           (stdin-takeover! runtime-ref proc)

           ;; FIXME: more graceful shutdown of the node-proc?
           (.destroy node-proc)
           (.waitFor node-proc)

           (when (.exists node-script)
             (.delete node-script))

           :cljs/quit
           ))

       (finally
         (sys/shutdown-system @runtime-ref))))))

(defn- release-setup [{:keys [release] :as build}]
  (-> (cljs/init-state)
      (cljs/set-build-options
        {:optimizations :advanced
         :pretty-print false})
      (cond->
        release
        (cljs/set-build-options release))
      (cljs/find-resources-in-classpath)
      (assoc ::build build)
      ))

(defn release [& args]
  (let [config (config/load-cljs-edn!)
        build (pick-build config args)
        state (release-setup build)]

    (case (:target build)
      :browser
      (-> state
          (configure-browser-build)
          (cljs/compile-modules)
          (cljs/closure-optimize)
          (cljs/flush-modules-to-disk))

      :script
      (-> state
          (configure-script-build)
          (assoc :optimizations :simple)
          (node/compile)
          (node/optimize)
          (node/flush))

      :library
      (-> state
          (configure-library-build)
          (assoc :optimizations :simple)
          (node/compile)
          (node/optimize)
          (umd/flush-module))))
  :done)

(defn- test-setup []
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (cljs/set-build-options
        {:public-dir (io/file "target" "cljs-test")
         :public-path "target/cljs-test"})
      (cljs/find-resources-in-classpath)
      ))

(defn autotest
  [& args]
  (-> (test-setup)
      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cond->
                ;; first pass, run all tests
                (empty? modified)
                (node/execute-all-tests!)
                ;; only execute tests that might have been affected by the modified files
                (not (empty? modified))
                (node/execute-affected-tests! modified))
              )))))

(defn test-all []
  (-> (test-setup)
      (node/execute-all-tests!)
      ))

(defn test-affected [test-ns]
  (-> (test-setup)
      (node/execute-affected-tests! [(cljs/ns->cljs-file test-ns)])
      ))
