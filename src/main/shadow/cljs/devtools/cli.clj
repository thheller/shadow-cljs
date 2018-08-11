(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.main :as main]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.cli-opts :as opts]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]
            [shadow.build.api :as cljs]
            [shadow.build.node :as node]
            [shadow.cljs.devtools.server.socket-repl :as socket-repl]
            [shadow.cljs.devtools.server.env :as env]
            [shadow.cljs.devtools.server.runtime :as runtime])
  (:import (clojure.lang LineNumberingPushbackReader)
           (java.io StringReader)))

;; delayed require to we can improve startup time a bit
(defn lazy-invoke [var-sym & args]
  (require (-> var-sym namespace symbol))
  (let [var (find-var var-sym)]
    (apply var args)))

(defn do-build-command [{:keys [action options] :as opts} build-config]
  (try
    (case action
      :release
      (api/release* build-config options)

      :check
      (api/check* build-config options)

      :compile
      (api/compile* build-config options))

    :success
    (catch Exception e
      e)))

(defn maybe-rethrow-exceptions [ex]
  (case (count ex)
    0 :success
    1 (throw (first ex))
    (throw (ex-info "multiple builds failed" {:exceptions ex}))
    ))

(defn do-build-commands
  [{:keys [builds] :as config} {:keys [action options] :as opts} build-ids]
  ;; FIXME: this should start classpath/npm services so builds can use it
  ;; if this is not a server instance

  (->> build-ids
       (map (fn [build-id]
              (or (get builds build-id)
                  (do (println (str "No config for build \"" (name build-id) "\" found."))
                      ;; FIXME: don't repeat this
                      (println (str "Available builds are: " (->> builds
                                                                  (keys)
                                                                  (map name)
                                                                  (str/join ", "))))))))
       (remove nil?)
       (map #(future (do-build-command opts %)))
       (into []) ;; force lazy seq
       (map deref)
       (remove #{:success})
       (into []) ;; force again
       ;; need to throw exceptions to ensure that cli commands can exit with proper exit codes
       (maybe-rethrow-exceptions)))

(defn do-clj-eval [config {:keys [arguments options] :as opts}]
  (let [in
        (if (:stdin options)
          *in*
          (-> (str/join " " arguments)
              (StringReader.)
              (LineNumberingPushbackReader.)))]

    (binding [*in* in]
      (main/repl
        :init #(socket-repl/repl-init {:print false})
        :prompt (fn [])))
    ))

(defn do-clj-run [config {:keys [arguments options] :as opts}]
  (let [[main & args]
        arguments

        main-sym
        (symbol main)

        main-sym
        (if (nil? (namespace main-sym))
          (symbol main "-main")
          main-sym)

        main-ns
        (namespace main-sym)]

    (try
      (require (symbol main-ns))
      (catch Exception e
        (throw (ex-info
                 (format "failed to load namespace: %s" main-ns)
                 {:tag ::clj-run-load
                  :main-ns main-ns
                  :main-sym main-sym} e))))

    (let [main-var (find-var main-sym)]
      (if-not main-var
        (println (format "could not find %s" main-sym))
        (let [main-meta (meta main-var)]
          (try
            (cond
              ;; running in server
              (runtime/get-instance)
              (apply main-var args)

              ;; new jvm. task fn wants to run watch
              (:shadow/requires-server main-meta)
              (do (lazy-invoke 'shadow.cljs.devtools.server/start!)
                  (apply main-var args)
                  (lazy-invoke 'shadow.cljs.devtools.server/wait-for-stop!))

              ;; new jvm. task fn doesn't need watch
              :else
              (api/with-runtime
                (apply main-var args)))
            (catch Exception e
              (throw (ex-info
                       (format "failed to run function: %s" main-sym)
                       {:tag ::clj-run
                        :main-sym main-sym}
                       e)))))))))

(defn blocking-action
  [config {:keys [action builds options] :as opts}]
  (binding [*in* *in*]
    (cond
      (= :clj-eval action)
      (api/with-runtime
        (do-clj-eval config opts))

      (or (= :clj-run action)
          (= :run action))
      (do-clj-run config opts)

      (contains? #{:watch :node-repl :browser-repl :cljs-repl :clj-repl :server} action)
      (lazy-invoke 'shadow.cljs.devtools.server/from-cli action builds options)
      )))

(defn main [& args]
  (let [{:keys [action builds options summary errors] :as opts}
        (opts/parse args)

        config
        (config/load-cljs-edn!)]

    ;; always install since its a noop if everything is in package.json
    ;; and a server restart is not required for them to be picked up
    (npm-deps/main config opts)

    ;; FIXME: need cleaner with-runtime logic
    ;; don't like that some actions implicitely start a server
    ;; while others don't
    ;; I think server should be a dedicated action but that only makes sense once we have a UI

    (cond
      ;;
      ;; actions that do a thing and exit
      ;;
      (:version options)
      (println "TBD")

      (or (:help options) (seq errors))
      (opts/help opts)

      ;;(= action :test)
      ;;(api/test-all)

      (= :npm-deps action)
      (println "npm-deps done.")

      (contains? #{:compile :check :release} action)
      (api/with-runtime
        (do-build-commands config opts builds))

      ;; MVP, really not sure where to take this
      (= :test action)
      (api/with-runtime
        (api/test))

      ;;
      ;; actions that may potentially block
      ;;
      (contains? #{:watch :node-repl :browser-repl :cljs-repl :clj-repl :server :clj-eval :clj-run :run} action)
      (blocking-action config opts)

      :else
      (println "Unknown action.")
      )))

(defn print-main-error [e]
  (try
    (errors/user-friendly-error e)
    (catch Exception ignored
      ;; print failed, don't attempt to print anything again
      )))

(defn print-token
  "attempts to print the given token to stdout which may be a socket
   if the client already closed the socket that would cause a SocketException
   so we ignore any errors since the client is already gone"
  [token]
  (try
    (println token)
    (catch Exception e
      ;; CTRL+D closes socket so we can't write to it anymore
      )))

(defn from-remote
  "the CLI script calls this with 2 extra token (uuids) that will be printed to notify
   the script whether or not to exit with an error code, it also causes the client to
   disconnect instead of us forcibly dropping the connection"
  [complete-token error-token args]
  (try
    (if (env/restart-required?)
      (do (println "SERVER INSTANCE OUT OF DATE!\nPlease restart.")
          (print-token error-token))
      (do (apply main "--via" "remote" args)
          (print-token complete-token)))
    (catch Exception e
      (print-main-error e)
      (println error-token))))

;; direct launches don't need to mess with tokens
(defn -main [& args]
  (try
    (apply main "--via" "main" args)
    (shutdown-agents)
    (catch Exception e
      (print-main-error e)
      (System/exit 1))))

(comment
  (defn autotest
    "no way to interrupt this, don't run this in nREPL"
    []
    (-> (api/test-setup)
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
    (api/test-all))

  (defn test-affected [test-ns]
    (api/test-affected [(cljs/ns->cljs-file test-ns)])))