(ns shadow.cljs.devtools.server.util
  (:require [clojure.core.async :as async :refer (go thread <! >! alt!! alts!!)]
            [shadow.cljs.log :as shadow-log]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.errors :as errors]
            [clojure.string :as str])
  (:import (java.io Writer InputStreamReader BufferedReader IOException)))

(defn async-logger [ch]
  (reify
    shadow-log/BuildLog
    (log*
      [_ state event]
      (async/offer! ch {:type :build-log
                        :event event}))))

(def null-log
  (reify
    shadow-log/BuildLog
    (log* [_ state msg])))

(defn print-warnings [warnings]
  (doseq [{:keys [msg line column source-name] :as w} warnings]
    (println (str "WARNING: " msg " (" (or source-name "<stdin>") " at " line ":" column ")"))))

(defn print-build-start [build-config]
  (println (format "[%s] Compiling ..." (:id build-config))))


(def separator "------------------------------------------------------------------------")

;; https://en.wikipedia.org/wiki/ANSI_escape_code
;; https://github.com/chalk/ansi-styles/blob/master/index.js

(def sgr-pairs
  {:reset [0, 0]
   :bold [1 22]
   :dim [2 22] ;; cursive doesn't support this
   :yellow [33 39] ;; cursive doesn't seem to support 39
   })

(defn ansi-seq [codes]
  (str \u001b \[ (str/join ";" codes) \m))

(defn coded-str [codes s]
  (let [open
        (->> (map sgr-pairs codes)
             (map first))
        close
        (->> (map sgr-pairs codes)
             (map second))]

    ;; FIXME: cursive doesn't support some ANSI codes
    ;; always reset to 0 sucks if there are nested styles
    (str (ansi-seq open) s (ansi-seq [0]))))

(defn sep-line [override offset]
  (let [prefix (subs separator 0 offset)
        len (count override)
        suffix (subs separator (+ offset len))]
    (str prefix override suffix)))

(defn print-source-lines
  [start-idx lines transform]
  (->> (for [[idx text] (map-indexed vector lines)]
         (format "%4d | %s" (+ 1 idx start-idx) text))
       (map transform)
       (str/join "\n")
       (println)))

(defn dim [s]
  (coded-str [:dim] s))

(defn print-warning
  [{:keys [source-name file line column source-excerpt msg] :as warning}]
  (when source-excerpt
    (println (coded-str [:bold] (sep-line (str " WARNING #" (::idx warning) " ") 6)))
    (println)
    (println (str " " (coded-str [:yellow :bold] msg)))
    (println)
    (println " File:" (if file
                        (str file ":" line ":" column)
                        source-name))
    (println separator)
    (let [{:keys [start-idx before line after]} source-excerpt]
      (print-source-lines start-idx before dim )
      (print-source-lines (+ start-idx (count before)) [line] #(coded-str [:bold] %))
      (let [col (+ 7 (or column 1))
            len (count line)

            prefix
            (->> (repeat (- col 3) " ")
                 (str/join ""))]

        (println (sep-line "^" (dec col)))
        (println (str " " (coded-str [:yellow :bold] msg)))
        (println separator))

      (when (seq after)
        (print-source-lines (+ start-idx (count before) 1) after dim)
        (println separator))
      (println))))

(defn print-build-complete [build-info build-config]
  (let [{:keys [sources compiled]}
        build-info

        warnings
        (->> (for [{:keys [warnings name]} sources
                   warning warnings]
               (assoc warning :source-name name))
             (into []))]

    (println (format "[%s] Build completed. (%d files, %d compiled, %d warnings, %.2fs)"
               (:id build-config)
               (count sources)
               (count compiled)
               (count warnings)
               (-> (- (get-in build-info [:flush :exit])
                      (get-in build-info [:compile-prepare :enter]))
                   (double)
                   (/ 1000))))

    (when (seq warnings)
      (println)
      (doseq [[idx w] (map-indexed vector warnings)]
        (print-warning (assoc w ::idx (inc idx))))
      )))

(defn print-build-failure [{:keys [build-config e] :as x}]
  (println (format "[%s] Build failure:" (:id build-config)))
  (errors/user-friendly-error e)
  )

(defn print-worker-out [x verbose]
  (locking cljs/stdout-lock
    (binding [*out* *err*]
      (case (:type x)
        :build-log
        (when verbose
          (println (shadow-log/event-text (:event x))))

        :build-configure
        (let [{:keys [build-config]} x]
          (println (format "[%s] Configuring build." (:id build-config))))

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
                                  (prn [:handler-error msg ex])
                                  (if (ifn? on-error)
                                    (on-error state msg ex)
                                    ;; FIXME: silently dropping e if no on-error is defined is bad
                                    (do (prn [:server-thread-ex ex])
                                        state))))]
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

