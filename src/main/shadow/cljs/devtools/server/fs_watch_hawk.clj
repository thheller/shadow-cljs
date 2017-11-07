(ns shadow.cljs.devtools.server.fs-watch-hawk
  (:require [clojure.core.async :as async :refer (thread alt!!)]
            [hawk.core :as hawk]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(defn buffer-loop [hawk-in publish-fn]
  ;; this loop buffers all hawk watch events so they can be processed in batch
  ;; otherwise we would get one event per file and each would trigger a recompile
  (loop [buffer []]
    (alt!!
      hawk-in
      ([msg]
        (when (some? msg)
          (-> buffer
              (conj msg)
              (recur))))

      (async/timeout 250)
      ([_]
        (when (seq buffer)
          (publish-fn buffer))
        (recur [])
        ))))

(defn start [directories file-exts publish-fn]
  (let [hawk-in
        (-> (async/sliding-buffer 100)
            (async/chan))
        
        buffer-thread
        (thread (buffer-loop hawk-in publish-fn))

        file-exts
        (into #{} file-exts)

        hawk
        (hawk/watch!
          (->> directories
               (map (fn [root]
                      (let [root-prefix (.getAbsolutePath root)
                            root-prefix-len (inc (count root-prefix))]

                        {:paths [(.getAbsolutePath root)]
                         :handler
                         (fn [ctx {:keys [file kind] :as e}]
                           (when (and (.isFile file)
                                      (not (.isHidden file)))
                             (try
                               (let [abs-name (.getAbsolutePath file)]
                                 (assert (str/starts-with? abs-name root-prefix))

                                 ;; special case hack when watching the public dir
                                 ;; we don't want to watch the files we write
                                 (when-not (str/includes? abs-name "cljs-runtime")
                                   (let [name
                                         (subs abs-name root-prefix-len)

                                         ext
                                         (when-let [x (str/index-of name ".")]
                                           (subs name (inc x)))]

                                     (when (contains? file-exts ext)
                                       (async/offer! hawk-in
                                         {:dir root
                                          :name name
                                          :ext ext
                                          :file file
                                          :event
                                          (case kind
                                            :create :new
                                            :modify :mod
                                            ;; don't seem to get delete events?
                                            :delete :del)})))))
                               (catch Exception ex
                                 (log/debugf ex "failed to process hawk event %s" e))))

                           ctx)})))
               (into [])))]
    
    {:hawk-in hawk-in
     :buffer-thread buffer-thread
     :hawk hawk}))

(defn stop [{:keys [hawk-in hawk buffer-thread] :as x}]
  (hawk/stop! hawk)
  (async/close! hawk-in)
  (async/<!! buffer-thread))

