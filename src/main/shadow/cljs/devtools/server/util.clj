(ns shadow.cljs.devtools.server.util
  (:require [clojure.core.async :as async :refer (go thread <! >! alt!! alts!!)]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [shadow.build.log :as build-log]
            [shadow.build.api :as cljs]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.build.warnings :as warnings])
  (:import (java.io Writer InputStreamReader BufferedReader IOException ByteArrayOutputStream ByteArrayInputStream)))

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

(defn print-warnings [warnings]
  (doseq [{:keys [msg line column source-name] :as w} warnings]
    (println (str "WARNING: " msg " (" (or source-name "<stdin>") " at " line ":" column ")"))))

(defn print-build-start [build-config]
  (println (format "[%s] Compiling ..." (:build-id build-config))))

(defn print-build-complete [build-info build-config]
  (let [{:keys [sources compiled]}
        build-info

        warnings
        (->> (for [{:keys [warnings resource-name]} sources
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (into []))]

    (println (format "[%s] Build completed. (%d files, %d compiled, %d warnings, %.2fs)"
               (:build-id build-config)
               (count sources)
               (count compiled)
               (count warnings)
               (-> (- (or (get-in build-info [:timings :flush :exit])
                          (get-in build-info [:timings :compile-finish :exit]))
                      (get-in build-info [:timings :compile-prepare :enter]))
                   (double)
                   (/ 1000))))

    (when (seq warnings)
      (println)
      (warnings/print-warnings warnings)

      )))

(defn print-build-failure [{:keys [build-config e] :as x}]
  (println (format "[%s] Build failure:" (:build-id build-config)))
  (errors/user-friendly-error e))

(defn print-worker-out [x verbose]
  (locking build-log/stdout-lock
    (binding [*out* *err*]
      (case (:type x)
        :build-log
        (when verbose
          (println (build-log/event-text (:event x))))

        :build-configure
        (let [{:keys [build-config]} x]
          (println (format "[%s] Configuring build." (:build-id build-config))))

        :build-start
        (print-build-start (:build-config x))

        :build-failure
        (print-build-failure x)

        :build-complete
        (let [{:keys [info build-config]} x]
          (print-build-complete info build-config))

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

        :repl/eval-start
        (println "JS runtime connected.")

        :repl/eval-stop
        (println "JS runtime disconnected.")

        :worker-shutdown
        (println "Worker shutdown.")

        :println
        (println (:msg x))

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

(defn server-thread
  "options

  :on-error (fn [state msg ex] state)
  :validate (fn [state])
  :validate-error (fn [state-before state-after msg] state-before)
  :do-shutdown (fn [last-state])"

  [state-ref init-state dispatch-table
   {:keys [validate validate-error on-error do-shutdown] :as options}]
  (let [chans
        (into [] (keys dispatch-table))]

    (thread
      (let [last-state
            (loop [state
                   init-state]
              (vreset! state-ref state)

              (let [[msg ch]
                    (alts!! chans)

                    handler
                    (get dispatch-table ch)]

                (if (nil? handler)
                  state
                  (if (nil? msg)
                    state
                    (-> (let [state-after
                              (try
                                (handler state msg)
                                (catch Throwable ex
                                  (log/warnf ex "failed to handle server msg: %s" msg)
                                  (if (ifn? on-error)
                                    (on-error state msg ex)
                                    ;; FIXME: silently dropping e if no on-error is defined is bad
                                    state)))]
                          (if (and (ifn? validate)
                                   (not (validate state-after)))
                            (validate-error state state-after msg)
                            state-after))
                        (recur))))))]

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
              (.printStackTrace e *err*))))
        (recur buf)))))

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