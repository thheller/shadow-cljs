(ns shadow.fswatch.macos
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [java.io BufferedReader File FileOutputStream InputStreamReader OutputStreamWriter]
    [java.util List]))

(defonce bin-path-ref (atom nil))

(defn bg-publish [publish-chan file-exts publish-fn]
  (loop [buffer {}]
    (async/alt!!
      publish-chan
      ([update]
       (when-not (nil? update)
         (let [[kind path file] update

               kind (keyword kind)
               key [path file]

               ;; since dealing with two different buffers we need some base logic
               ;; editors such as IntelliJ often create a lot of temp files
               ;; which we aren't really interested in
               ;; avoid doing too much work if multiple file ops occur in buffer window
               buffer
               (case kind
                 :new
                 (case (get buffer key)
                   ;; file was deleted and created, record as modified
                   :del (assoc buffer key :mod)
                   (assoc buffer key :new))

                 :mod
                 (case (get buffer key)
                   ;; file was created and then modified, keep as :new
                   :new buffer
                   (assoc buffer key :mod))

                 :del
                 (case (get buffer key)
                   ;; file was created and removed, pretend it never existed
                   :new (dissoc buffer key)
                   (assoc buffer key :del)))]

           (recur buffer))))

      ;; binary potentially pushes a lof of updates in batches
      ;; it uses an internal 500ms buffer period already so we only want to wait a litte bit
      ;; before flushing our update so we don't publish while still in batch
      (async/timeout 100)
      ([_]
       (when (seq buffer)
         (let [updates
               (reduce-kv
                 (fn [updates [path filename] event]
                   (let [dir (io/file path)
                         file (io/file dir filename)
                         ext (when-let [x (str/last-index-of filename ".")]
                               (subs filename (inc x)))]

                     (if (and (seq ext)
                              (contains? file-exts ext)
                              (or (= :del event)
                                  (pos? (.length file))))
                       (conj updates
                         {:dir dir
                          :name filename
                          :ext ext
                          :file file
                          :event event})
                       updates)))
                 []
                 buffer)]

           (when (seq updates)
             (publish-fn updates))))

       (recur {})))))

(defn bg-proc [state-ref publish-chan config directories]
  (loop []
    (let [bin-path
          @bin-path-ref

          _
          (when (nil? bin-path)
            (throw (ex-info "shadow.fswatch.macos/setup not completed!" {})))

          proc
          (-> (ProcessBuilder.
                ^List
                (-> [bin-path]
                    (into (map (fn [^File dir] (.getCanonicalPath dir)))
                      directories)))
              (.start))

          proc-in
          (-> (.getInputStream proc)
              (InputStreamReader.)
              (BufferedReader.))]

      ;; --stdin alternative
      #_(with-open [proc-out
                    (-> (.getOutputStream proc)
                        (OutputStreamWriter.))]
          (doseq [dir directories]
            (let [^String abs-path (.getAbsolutePath dir)]
              (.write proc-out abs-path)
              (.write proc-out "\n"))))

      (swap! state-ref assoc :proc proc)

      (loop []
        (when-some [line (.readLine proc-in)]
          (let [update (str/split line #"," 3)]
            (async/>!! publish-chan update))
          (recur))))

    (when-not (:shutdown @state-ref)
      (recur)))

  (async/close! publish-chan)
  (swap! state-ref dissoc :proc))

(defn setup [sys-config]
  (let [bin-file (.getCanonicalFile (io/file (:cache-root sys-config ".shadow-cljs") "macos-fswatch"))]

    ;; extract bin file from jar so we can call it
    (with-open [bin-in (-> (Thread/currentThread) (.getContextClassLoader) (.getResourceAsStream "shadow/fswatch/macos-fswatch"))]
      (with-open [file-out (FileOutputStream. bin-file false)]
        (.transferTo bin-in file-out)))

    (.setExecutable bin-file true)

    (reset! bin-path-ref (.getCanonicalPath bin-file))
    ))

(defn start [config directories file-exts publish-fn]
  (let [state-ref
        (atom {})

        publish-chan
        (async/chan (async/dropping-buffer 128))

        bg-publish-thread
        (-> (Thread/ofVirtual)
            (.name "shadow.fswatch.macos/bg-publish")
            (.start #(bg-publish publish-chan (set file-exts) publish-fn)))

        bg-proc-thread
        (-> (Thread/ofVirtual)
            (.name "shadow.fswatch.macos/bg-proc")
            (.start #(bg-proc state-ref publish-chan config directories)))]

    {:state-ref state-ref
     :publish-chan publish-chan
     :bg-proc-thread bg-proc-thread
     :bg-publish-thread bg-publish-thread}))

(defn stop [{:keys [state-ref bg-proc-thread] :as watcher}]
  (swap! state-ref assoc :shutdown true)

  (when-some [^Process proc (:proc @state-ref)]
    (.destroy proc))

  (.join bg-proc-thread))

(comment
  (setup {})
  (def w (start {}
           [(io/file "src/main")
            (io/file "src/test")]
           ["clj" "cljs" "cljc" "js"]
           prn))

  (stop w)
  )