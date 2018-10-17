(ns shadow.cljs.npm.util
  (:require
    ["child_process" :as cp]
    ["fs" :as fs]))

(defn slurp [file]
  (-> (fs/readFileSync file)
      (.toString)))

(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))

(defn conj-set [x y]
  (if (nil? x)
    #{y}
    (conj x y)))

(defn kill-proc [^js proc]
  ;; the java process should now always exit cleanly when the node process is killed
  ;; so we just kill the node process to let the jvm shutdown cleanly
  (.kill proc)
  #_(case js/process.platform
      "win32"
      (cp/spawnSync "taskkill" #js ["/pid" (.-pid proc) "/f" #_"/t"])

      (.kill proc)
      ))