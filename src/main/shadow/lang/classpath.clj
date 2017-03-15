(ns shadow.lang.classpath
  (:require [clojure.java.io :as io]
            [shadow.cljs.build :as cljs]
            [clojure.string :as str]))

(defn- svc? [x]
  (and (map? x) (::service x)))

;; FIXME: needs to supports jars if the editor supports looking into jars
(defn get-classpath-entries []
  (->> (cljs/classpath-entries)
       (map io/file)
       (filter #(.isDirectory %))
       (map #(-> % (.getAbsolutePath)))
       (distinct)
       (into [])))

(defn match-to-classpath
  [{:keys [paths] :as svc} uri]
  {:pre [(svc? svc)]}
  (let [matches (filter #(str/starts-with? uri %) paths)]
    (when (= 1 (count matches))
      (let [classpath (first matches)]
        [classpath (subs uri (-> classpath (count) (inc)))]
        ))))

(defn start []
  {::service true
   :paths (get-classpath-entries)})

(defn stop [x]
  {:pre [(svc? x)]})


(comment
  (get-classpath-entries)

  (-> (start)
      (match-to-classpath "/Users/zilence/code/cmslib/src/dev/foo.cljs"))
  )
