(ns shadow.cljs.npm.terminal
  (:require ["readline" :as rl]
            ["chalk" :as chalk]
            [goog.string :as gstr]
            [goog.string.format]
            [clojure.string :as str]))

(defn clear []
  (rl/cursorTo js/process.stdout 0 0)
  (rl/clearScreenDown js/process.stdout))


(defn pad-right [x size]
  (let [s (str x)
        spaces
        (-> (- size (count s))
            (repeat " ")
            (str/join))]
    (str s spaces)
    ))

(def status-symbols
  {:complete "✔"
   :started "-"
   :error "⨯"})

(defn print-source-lines
  [start-idx lines transform]
  (->> (for [[idx text] (map-indexed vector lines)]
         (gstr/format "%4d | %s" (+ 1 idx start-idx) text))
       (map transform)
       (str/join "\n")
       (println)))

(defn render [{:keys [remote config supervisor version config-path] :as state}]
  ;; (clear)

  (let [{:keys [pending]} remote]
    (println (str "shadow-cljs - interactive mode [version: \"" version "\" pending: " (count pending) "]"))
    (println "   - config: " config-path)
    (println)

    (when config
      (println "build status:")
      (doseq [{:keys [id target]}
              (->> (:builds config)
                   (vals)
                   (sort-by :id))

              :let [{:keys [status] :as worker-state} (get supervisor id)]
              :when worker-state]

        (let [status-line
              (-> (str " " (get status-symbols status) " - " id)
                  (cond->
                    (= :complete status)
                    (chalk/green)
                    (= :error status)
                    (chalk/red)
                    ))]

          (println status-line)
          ))

      (println))

    (let [warnings
          (->> (for [[id worker-state] supervisor
                     :when (= :complete (:status worker-state))
                     {:keys [name warnings] :as src} (-> worker-state :info :build-info :sources)
                     warning warnings]
                 warning)
               (distinct)
               (sort-by :source-name)
               (into []))]

      (when (seq warnings)
        (println "build warnings:")

        (doseq [warning warnings]
          (let [{:keys [source-name file line column source-excerpt msg]} warning]
            (when source-excerpt
              (println "-----  WARNING --------------------------------------------")
              (println)
              (-> (str " " msg)
                  (chalk/yellow)
                  (println))
              (println)
              (println " File:" file)
              (println)
              (let [{:keys [start-idx before line after]} source-excerpt]
                (print-source-lines start-idx before chalk/dim)
                (print-source-lines (+ start-idx (count before)) [line] chalk/bold)
                (let [col (+ 7 (or column 0))
                      len (count line)

                      prefix
                      (->> (repeat (- col 3) " ")
                           (str/join ""))]

                  (-> (str prefix "--^--")
                      (chalk/bold)
                      (println))
                  (-> (str "      " msg)
                      (chalk/yellow)
                      (println)))

                (print-source-lines (+ start-idx (count before) 1) after chalk/dim))
              (println)
              (println "-----------------------------------------------------------")))
          )))))

(defn setup [state]
  xxx
  state)
