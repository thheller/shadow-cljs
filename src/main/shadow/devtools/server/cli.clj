(ns shadow.devtools.server.cli
  (:require [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer (go <! >! >!! <!!)]
            [shadow.cljs.node :as node]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.build :as cljs]
            [shadow.server.runtime :as rt]
            [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.config :as config]
            [shadow.devtools.server.compiler :as comp]
            [shadow.devtools.server.common :as common]
            [shadow.devtools.server.util :as util])

  (:import (java.lang ProcessBuilder$Redirect)))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

;; FIXME: spec for cli
(defn- parse-args [[build-id & more :as args]]
  {:build (keyword build-id)})

(defn once [& args]
  (let [{:keys [build] :as args}
        (parse-args args)

        build-config
        (config/get-build! build)]

    (-> (comp/init :dev build-config)
        (comp/compile)
        (comp/flush)))

  :done)

(defn stdin-takeover! [build-proc]

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


(defn- start []
  (let [cli-app
        (merge
          (common/app)
          {:proc
           {:depends-on [:fs-watch]
            :start build/start
            :stop build/stop}})]

    (-> {:config {}
         :out (util/stdout-dump)}
        (rt/init cli-app)
        (rt/start-all))))

(defn dev [& args]
  (let [{:keys [build] :as args}
        (parse-args args)

        build-config
        (config/get-build! build)

        {:keys [proc out fs-watch] :as app}
        (start)]

    (try
      (let [proc
            (-> proc
                (build/watch out)
                (build/configure build-config)
                (build/start-autobuild))]
        (stdin-takeover! proc))
      (finally
        (rt/stop-all app)))
    ))

(defn node-repl
  ([]
   (node-repl {}))
  ([{:keys [node-args
            node-command
            pwd]
     :or {node-args []
          node-command "node"}}]
   (let [{:keys [proc out fs-watch] :as app}
         (start)]
     (try
       (let [script-name
             "target/shadow-node-repl.js"

             build-config
             {:id :node-repl
              :target :script
              :main 'shadow.devtools.client.node-repl/main
              :output-to script-name}

             result
             (-> proc
                 (build/watch out)
                 (build/configure build-config)
                 (build/compile!))]

         ;; FIXME: validate that compilation succeeded

         (let [node-script
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

           ;; FIXME: validate that proc started

           (try

             (let [out (.getOutputStream node-proc)]
               ;; piping the script into node-proc instead of using command line arg
               ;; as node will otherwise adopt the path of the script as the require reference point
               ;; we want to control that via pwd
               (io/copy (slurp node-script) out)
               (.close out))

             ;; this blocks reading stdin
             (stdin-takeover! proc)

             (finally
               ;; FIXME: more graceful shutdown of the node-proc?
               (.destroy node-proc)
               (.waitFor node-proc)

               (when (.exists node-script)
                 (.delete node-script))))))

       (finally
         (rt/stop-all app))))

    (println "Node REPL shutdown. Goodbye ...")
    :cljs/quit
    ))

(defn release [& args]
  (let [{:keys [build] :as args}
        (parse-args args)

        build-config
        (config/get-build! build)]

    (-> (comp/init :release build-config)
        (comp/compile)
        (comp/flush)))
  :done)

(defn- test-setup []
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (cljs/set-build-options
        {:public-dir (io/file "target" "shadow-test")
         :public-path "target/shadow-test"})
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
