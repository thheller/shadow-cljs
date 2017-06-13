(ns shadow.inf-clojure
  (:require [compliment.core :as comp]))

(defn completions [prefix]
  (comp/completions prefix))

(comment
  (completions "conj"))