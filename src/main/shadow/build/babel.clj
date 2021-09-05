(ns shadow.build.babel
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async :refer (<!! >!!)]
            [shadow.build.log :as cljs-log]
            [shadow.cljs.util :as util]
            [shadow.core-ext :as core-ext]
            [shadow.jvm-log :as log]
            [clojure.string :as str])
  (:import (java.io PushbackReader Writer InputStreamReader BufferedReader IOException PrintWriter)))

(defn service? [x]
  (and (map? x) (::service x)))

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
              (.printStackTrace e ^PrintWriter *err*))))
        (recur buf)))))

(defn babel-install [work-dir cmd]

  (let [cmd
        (if (str/includes? (System/getProperty "os.name") "Windows")
          (into ["cmd" "/C"] cmd)
          cmd)

        _ (log/debug ::babel-install {:work-dir work-dir
                                      :cmd cmd})

        pb
        (-> (ProcessBuilder. cmd)
            (.directory work-dir))

        proc
        (.start pb)]

    (.start (Thread. (bound-fn [] (pipe proc (.getInputStream proc) *out*))))
    (.start (Thread. (bound-fn [] (pipe proc (.getErrorStream proc) *err*))))

    (let [exit (.waitFor proc)]
      (log/debug ::babel-install-exit {:code exit})

      (when-not (zero? exit)
        (throw (ex-info "babel install failed!" {:work-dir work-dir
                                                 :cmd cmd
                                                 :exit exit}))
        ))))

(defn maybe-start-proc [{:keys [proc] :as state}]
  (if (and proc (.isAlive proc))
    state
    (let [worker-dir ;; FIXME: use proper :cache-root config
          (-> (io/file ".shadow-cljs" "babel-worker")
              (.getCanonicalFile))

          worker-file
          (io/file worker-dir "babel-worker.js")

          dist-content
          (slurp (io/resource "shadow/cljs/dist/babel-worker.js"))]

      (when (or (not (.exists worker-file))
                (not= dist-content (slurp worker-file)))
        (log/debug ::worker-copy {})
        (io/make-parents worker-file)
        (spit worker-file dist-content))

      (log/debug ::start {})

      (let [proc
            (-> (ProcessBuilder. ["node" (.getAbsolutePath worker-file)])
                (.directory nil)
                (.start))]

        ;; FIXME: errors need to go somewhere else, this is not reliable
        (.start (Thread. (bound-fn [] (pipe proc (.getErrorStream proc) *err*))))

        (assoc state
          :proc proc
          :in (PushbackReader. (io/reader (.getInputStream proc)))
          :out (io/writer (.getOutputStream proc)))
        ))))

(defn babel-transform! [state {::keys [reply-to] :as req}]
  (try
    (let [{:keys [proc in out] :as state} (maybe-start-proc state)]
      ;; send request as one-line edn
      (let [line
            (-> req
                (dissoc ::reply-to)
                (core-ext/safe-pr-str)
                (str "\n"))]
        (doto out
          (.write line)
          (.flush)))

      ;; read one line
      (let [res (read in)]
        (>!! reply-to res))
      state)
    (catch Exception e
      (log/warn-ex e ::babel-transform-ex req)
      state)
    (finally
      (async/close! reply-to))))

(defn shutdown [{:keys [proc in out] :as state}]
  (when (and proc (.isAlive proc))
    (.close in)
    (.close out)
    (.destroy proc)
    (.waitFor proc)))

(defn babel-loop [babel-in]
  (loop [state {}]
    (if-some [req (<!! babel-in)]
      (recur (babel-transform! state req))
      (shutdown state))))

(defn start []
  (let [babel-in (async/chan 100)]
    {::service true
     :babel-in babel-in
     :babel-loop (async/thread (babel-loop babel-in))}))

(defn stop [{:keys [babel-in babel-loop] :as svc}]
  (async/close! babel-in)
  (<!! babel-loop))

(defn transform [{:keys [babel-in] :as svc} req]
  {:pre [(map? req)
         (contains? req :code)
         (contains? req :file)]}
  (let [reply-to (async/chan 1)
        req (assoc req ::reply-to reply-to)]
    (>!! babel-in req)
    (<!! reply-to)))

(defmethod cljs-log/event->str ::transform
  [{:keys [file] :as event}]
  (format "Babel transform: %s " file))

(defn convert-source [babel state source file-path]
  {:pre [(service? babel)
         (string? source)
         (string? file-path)]}
  (util/with-logged-time [state {:type ::transform
                                 :file file-path}]

    (let [{:keys [code] :as result}
          (transform babel {:code source
                            :file file-path
                            :preset-config
                            (get-in state [:js-options :babel-preset-config]
                              {:targets {"chrome" "90"}})})]
      (when-not (seq code)
        (throw (ex-info "babel failed?" (assoc result :file file-path))))

      code)))

(comment
  (let [{:keys [babel-in] :as svc} (start)]
    (prn [:started])

    (prn (transform svc {:code "let foo = 1;"
                         :file "test.js"
                         :preset-config
                         {
                          :targets {"chrome" "80"}}}))

    (stop svc)
    (prn [:done])
    :done))
