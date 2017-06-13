(ns shadow.inf-clojure
  (:require [compliment.core :as comp]))

(defn completions [prefix]
  (->> (comp/completions prefix)
       (map :candidate)
       (map str)))

(comment
  (completions "con"))