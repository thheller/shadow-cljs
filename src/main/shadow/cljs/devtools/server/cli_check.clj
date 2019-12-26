(ns shadow.cljs.devtools.server.cli-check
  (:import [java.lang ProcessHandle]))

(defn attach [ppid]
  (let [pho (ProcessHandle/of (Long/valueOf ppid))]
    (when (.isPresent pho)
      (let [ph (.get pho)]
        (-> (.onExit ph)
            (.thenRun (fn []
                        ;; this will trigger the regular shutdown hooks
                        (System/exit 0)))
            )))))
