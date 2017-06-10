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

(defn render-warnings [{:keys [remote config supervisor version config-path] :as state}]
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
            (println (str " File: " (if file
                                      (str file ":" line)
                                      source-name)))
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
                (-> (str "       " msg)
                    (chalk/yellow)
                    (println)))

              (print-source-lines (+ start-idx (count before) 1) after chalk/dim))
            (println)
            (println "-----------------------------------------------------------")))
        ))))

(defn render-build-state [{:keys [remote config supervisor version config-path] :as state}]
  (when config
    (println "build status:")
    (doseq [{:keys [id target]}
            (->> (:builds config)
                 (vals)
                 (sort-by :id))

            :let [{:keys [status] :as worker-state} (get supervisor id)]
            :when worker-state]

      (let [status-line
            (-> (str "    " (get status-symbols status) " " id)
                (cond->
                  (= :complete status)
                  (chalk/green)
                  (= :error status)
                  (chalk/red)
                  ))]
        (println status-line)
        ))
    (println)))

(defn render-header [{:keys [remote config supervisor version config-path] :as state}]
  (let [{:keys [pending]} remote]
    (println (str "shadow-cljs - interactive mode [version: \"" version "\" pending: " (count pending) "]"))
    (println "   - config: " config-path)
    (println)))

(defn render [{:keys [rl-interface remote config supervisor version config-path input-error] :as state}]
  ;; FIXME: not sure this is needed?
  (.pause rl-interface)

  ;; clear makes debugging a nightmare
  (clear)

  (comment
    (println)
    (println)
    (println "==========================================")
    (println)
    (println))

  (render-header state)
  (render-build-state state)
  (render-warnings state)

  (when input-error
    (println)
    (println input-error)
    (println))

  (println "type quit to exit")
  (.prompt rl-interface true)
  (.resume rl-interface))

(defn setup [state]
  state)
