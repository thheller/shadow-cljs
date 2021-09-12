(ns git-tags
  (:require
    [clojure.java.shell :refer (sh)]
    [clojure.string :as str]))


(defn git-tags []
  (-> (sh "git" "tag" "--list")
      (get :out)
      (str/split #"\n")
      (set)))

(comment
  (git-tags))

(defn -main [& args])
