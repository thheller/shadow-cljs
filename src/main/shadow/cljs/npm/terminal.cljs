(ns shadow.cljs.npm.terminal
  (:require ["readline" :as rl]))

(defn clear []
  (rl/cursorTo js/process.stdout 0 0)
  (rl/clearScreenDown js/process.stdout))

(defn render [{:keys [remote builds workers] :as state}]
  ;; (clear)

  (let [{:keys [pending]} remote]
    (println (str "shadow-cljs - interactive mode [pending: " (count pending) "]"))

    (println "build status: ")
    (doseq [{:keys [id target]}
            (->> builds
                 (vals)
                 (sort-by :id))]

      (let [worker-state (get workers id)]

        (println [:build id target worker-state]))
      )))

(defn setup [state]
  state)
