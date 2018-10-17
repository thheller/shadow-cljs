(ns shadow.cljs.devtools.server.util
  (:require
    [clojure.core.async :as async :refer (go <! >! alt!! alts!!)]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [shadow.jvm-log :as log]
    [shadow.build.log :as build-log]
    [shadow.build.api :as cljs]
    [shadow.build.warnings :as warnings]
    [shadow.build.api :as build-api]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.runtime :as runtime]
    [clojure.set :as set]
    [clojure.core.async.impl.protocols :as async-prot])
  (:import (java.io Writer InputStreamReader BufferedReader IOException ByteArrayOutputStream ByteArrayInputStream)))

(defn chan? [x]
  (satisfies? async-prot/ReadPort x))

(defn find-version-from-pom [pom-xml]
  (let [[_ version :as m]
        (->> (slurp pom-xml)
             (re-find #"<version>([^<]+)</version>"))]
    (when m
      version)))

(defn find-version []
  (let [pom-xml (io/resource "META-INF/maven/thheller/shadow-cljs/pom.xml")]

    (if (nil? pom-xml)
      "<snapshot>"
      (find-version-from-pom pom-xml))))

(defn async-logger [ch]
  (reify
    build-log/BuildLog
    (log*
      [_ state event]
      (async/offer! ch {:type :build-log
                        :event event}))))

(def null-log
  (reify
    build-log/BuildLog
    (log* [_ state msg])))

(def silent-log
  (reify build-log/BuildLog
    (log* [_ state {::build-log/keys [level] :as evt}]
      (when (not= level :info)
        (build-log/log* build-log/stdout state evt)
        ))))

(defn new-build
  [{:keys [build-id] :or {build-id :custom} :as build-config} mode opts]
  (let [{:keys [npm classpath cache-root build-executor babel config] :as runtime}
        (runtime/get-instance!)

        {:keys [cache-blockers]}
        config

        cache-dir
        (config/make-cache-dir cache-root build-id mode)

        node-modules-dir
        (when-let [nmd (get-in build-config [:js-options :node-modules-dir])]
          (let [nmdf (io/file nmd)]
            (cond
              (and (str/ends-with? nmd "node_modules")
                   (.exists nmdf))
              (.getAbsoluteFile nmdf)

              (.exists (io/file nmdf "node_modules"))
              (-> (io/file nmdf "node_modules")
                  (.getAbsoluteFile))

              (.exists nmdf)
              (.getAbsoluteFile nmdf)

              :else
              (throw (ex-info "invalid :node-modules-dir" {:node-modules-dir nmd})))))

        npm
        (-> (select-keys (:js-options build-config) [:main-keys :extensions])
            (merge npm)
            (cond->
              node-modules-dir
              (assoc :node-modules-dir node-modules-dir)

              (get-in build-config [:js-options :prefer-esm])
              (assoc :main-keys ["module" "jsnext:main" "browser" "main"]
                     :extensions [".mjs" ".js" ".json"])))]

    (-> (build-api/init)
        (build-api/with-npm npm)
        (build-api/with-babel babel)
        (build-api/with-classpath classpath)
        (build-api/with-cache-dir cache-dir)
        (build-api/with-executor build-executor)
        ;; default logger logs everything
        ;; if not verbose replace with one that only logs warnings/errors
        (cond->
          (not (or (:verbose opts)
                   (:verbose config)))
          (build-api/with-logger silent-log)

          ;; allow :cache-blockers to be defined globally in addition to the build itself
          (set? cache-blockers)
          (update-in [:build-options :cache-blockers] set/union cache-blockers))

        (assoc :mode mode))))

(defn print-warnings [warnings]
  (doseq [{:keys [msg line column source-name] :as w} warnings]
    (println (str "WARNING: " msg " (" (or source-name "<stdin>") " at " line ":" column ")"))))

(defn print-build-start [{:keys [build-id] :as x}]
  (println (format "[%s] Compiling ..." build-id)))

(defn print-build-complete [{:keys [build-id info] :as x}]
  (let [{:keys [sources compiled]}
        info

        warnings
        (->> (for [{:keys [warnings resource-name]} sources
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (into []))]

    (println (format "[%s] Build completed. (%d files, %d compiled, %d warnings, %.2fs)"
               build-id
               (count sources)
               (count compiled)
               (count warnings)
               (-> (- (or (get info :flush-complete)
                          (get info :compile-complete))
                      (get info :compile-start))
                   (double)
                   (/ 1000))))

    (when (seq warnings)
      (println)
      (warnings/print-warnings warnings)

      )))

(defn print-build-failure [{:keys [build-id report] :as x}]
  (println (format "[%s] Build failure:" build-id))
  (println report)
  ;; no longer part of the message
  #_ (errors/user-friendly-error e))

(defn print-worker-out [x verbose]
  (locking build-log/stdout-lock
    (binding [*out* *err*]
      (case (:type x)
        :build-log
        (when verbose
          (println (build-log/event-text (:event x))))

        :build-configure
        (println (format "[%s] Configuring build." (:build-id x)))

        :build-start
        (print-build-start x)

        :build-failure
        (print-build-failure x)

        :build-complete
        (print-build-complete x)

        :build-shutdown
        (println "Build shutdown.")

        :repl/action
        (let [warnings (get-in x [:action :warnings])]
          (print-warnings warnings))

        ;; should have been handled somewhere else
        :repl/result
        :ignored

        :repl/error
        :ignored ;; also handled by out

        :repl/runtime-connect
        (println "JS runtime connected.")

        :repl/runtime-disconnect
        (println "JS runtime disconnected.")

        :worker-shutdown
        (println "Worker shutdown.")

        :println
        (println (:msg x))

        ;; handled in dedicated channel as they should not mix with build output
        :repl/out
        :ignored

        :repl/err
        :ignored

        ;; default
        (prn [:log x])))))

(defn stdout-dump [verbose]
  (let [chan
        (-> (async/sliding-buffer 1)
            (async/chan))]

    (async/go
      (loop []
        (when-some [x (<! chan)]
          (try
            (print-worker-out x verbose)
            (catch Exception e
              (prn [:stdout-dump-ex e])))
          (recur)
          )))

    chan
    ))

(defmacro thread
  "same as core async thread but does not preserve bindings"
  [name & body]
  `(let [c# (async/promise-chan)
         t# (Thread.
              (^:once fn* []
                (try
                  (when-some [result# (do ~@body)]
                    (async/>!! c# result#))
                  (catch Exception e#
                    (log/warn-ex e# ::thread-ex {:name ~name}))
                  (finally
                    (async/close! c#)))))]
     (.start t#)
     c#))

(defn server-thread
  "options

  :on-error (fn [state msg ex] state)
  :validate (fn [state])
  :validate-error (fn [state-before state-after msg] state-before)
  :do-shutdown (fn [last-state])"

  [thread-name state-ref init-state dispatch-table
   {:keys [server-id idle-action idle-timeout validate validate-error on-error do-shutdown] :as options}]
  (let [chans
        (into [] (keys dispatch-table))]

    (thread thread-name
      (let [last-state
            (loop [state init-state]
              (vreset! state-ref state)

              (let [timeout-chan
                    (when idle-action
                      (async/timeout (or idle-timeout 500)))

                    [msg ch]
                    (alts!! (cond-> chans timeout-chan (conj timeout-chan)))]

                (cond
                  (identical? ch timeout-chan)
                  (-> (try
                        (idle-action state)
                        (catch Exception ex
                          (log/warn-ex ex ::idle-ex)
                          state))
                      (recur))

                  (nil? msg)
                  state

                  :else
                  (let [handler (get dispatch-table ch)]
                    (if (nil? handler)
                      state
                      (-> (let [state-after
                                (try
                                  (handler state msg)
                                  (catch Throwable ex
                                    (log/warn-ex ex ::handle-ex {:msg msg})
                                    (if (ifn? on-error)
                                      (on-error state msg ex)
                                      ;; FIXME: silently dropping e if no on-error is defined is bad
                                      state)))]
                            (if (and (ifn? validate)
                                     (not (validate state-after)))
                              (validate-error state state-after msg)
                              state-after))
                          (recur)))))))]

        (if-not do-shutdown
          last-state
          (do-shutdown last-state)
          )))))

;; https://github.com/clojure/clojurescript/blob/master/src/main/clojure/cljs/repl/node.clj
;; I would just call that but it is private ...
(defn pipe [^Process proc in ^Writer out]
  ;; we really do want system-default encoding here
  (with-open [^java.io.Reader in (-> in InputStreamReader. BufferedReader.)]
    (loop [buf (char-array 1024)]
      (when (.isAlive proc)
        (try
          (let [len (.read in buf)]
            (when-not (neg? len)
              (.write out buf 0 len)
              (.flush out)))
          (catch IOException e
            (when (and (.isAlive proc) (not (.contains (.getMessage e) "Stream closed")))
              (when *err*
                (.printStackTrace e *err*)))))
        (recur buf)))))

(defn wsl-ify [path]
  (-> path
      (str/replace "/mnt/c/" "C:\\")
      (str/replace "/" "\\")))

(defn make-open-arg
  [{:keys [file line column] :as data} opt]
  (cond
    (string? opt)
    opt

    (vector? opt)
    (let [[fmt & args]
          opt

          args
          (into [] (map #(make-open-arg data %)) args)]
      (apply format fmt args))

    (= opt :pwd)
    (-> (io/file "")
        (.getAbsolutePath))

    (= opt :wsl-pwd)
    (-> (io/file "")
        (.getAbsolutePath)
        (wsl-ify))

    (= opt :wsl-file)
    (wsl-ify file)

    (= opt :file)
    file

    (= opt :line)
    (str (or line 1))

    (= opt :column)
    (str (or column 1))

    :else
    (throw (ex-info "invalid literal in :open-file-command" {:opt opt}))
    ))

(defn make-open-args
  "transforms :open-file-command template by replacing keywords with actual values"
  [data template]
  (let [template
        (cond
          (vector? template)
          template

          (keyword? template)
          (case template
            :idea
            ;; /usr/local/bin/idea [-l|--line line] [project_dir|--temp-project] file[:line]
            ;; --line doesn't seem to work properly
            ["idea" :pwd ["%s:%s" :file :line]]
            :emacs
            ["emacsclient" "-n" ["+%s:%s" :line :column] :file]
            (throw (ex-info "no :open-file-command template by that name" {:template template})))

          :else
          (throw (ex-info "invalid :open-file-command" {:template template})))]

    (into [] (map #(make-open-arg data %)) template)))

(defn launch
  "clojure.java.shell/sh replacement since kw-args suck"
  [args {:keys [pwd in] :as opts}]
  (let [proc
        (-> (ProcessBuilder. (into-array args))
            (.directory
              ;; nil defaults to JVM working dir
              (when pwd
                (io/file pwd)))
            (.start))

        proc-out
        (ByteArrayOutputStream.)

        proc-err
        (ByteArrayOutputStream.)]

    (future (io/copy (.getInputStream proc) proc-out))
    (future (io/copy (.getErrorStream proc) proc-err))

    (when (string? in)
      (with-open [proc-in (.getOutputStream proc)
                  bais (-> (.getBytes in) (ByteArrayInputStream.))]
        (io/copy bais proc-in)))

    (let [result (.waitFor proc)]

      {:exit result
       :err (.toString proc-err (or (:err-enc opts)
                                    (:enc opts)
                                    "UTF-8"))
       :out (.toString proc-out (or (:out-enc opts)
                                    (:enc opts)
                                    "UTF-8"))})))