(ns changelog
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :refer (sh)]
    [clojure.string :as str])
  (:import [java.io StringWriter]))

(defn group-by-bumps [all]
  (loop [groups []
         group {:version "Upcoming ..." :commits []}
         [commit & more] all]
    (cond
      (nil? commit)
      (conj groups group)

      (re-find #"^bump (\d+)\.(\d+)\.(\d+)" (:message commit))
      (let [version (subs (:message commit) 5)]
        (recur
          (conj groups (assoc group :prev-version version))
          {:version version
           :sha (:sha commit)
           :commits []}
          more))

      ;; ignore changelog related commits completely
      (str/starts-with? (:message commit) "changelog")
      (recur groups group more)

      :else
      (recur
        groups
        (update group :commits conj commit)
        more))))


(defn gather-history []
  (let [lines
        (-> (sh "git" "log" "--format=oneline")
            (get :out)
            (str/split #"\n"))]

    (->> lines
         (map (fn [line]
                {:sha (subs line 0 40)
                 :message (subs line 41)}))
         (group-by-bumps)
         (vec))))

(defn to-markdown [history]
  (let [sw (StringWriter.)]
    (binding [*out* sw]
      (println "# Changelog")
      (println)
      (doseq [{:keys [version commits]}
              ;; drop 3 old versions where I didn't bump properly, too old to be interesting anyways
              (subvec history 1 (- (count history) 3))]
        (let [last-sha (:sha (last commits))
              first-sha (:sha (first commits))]

          (println (str "## [" version "](https://github.com/thheller/shadow-cljs/compare/" last-sha "..." first-sha ")"))
          (doseq [{:keys [sha message]} commits]
            (println (str "- [ [`" (subs sha 0 5) "`](https://github.com/thheller/shadow-cljs/commit/" sha ") ] " message))))

        (println)))

    (.toString sw)))


(defn main* []
  (let [history (gather-history)]
    (spit (io/file "CHANGELOG.md") (to-markdown history))))

(comment
  (main*))

(defn -main []
  (main*)
  (shutdown-agents))

