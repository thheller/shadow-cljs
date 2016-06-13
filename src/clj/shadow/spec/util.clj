(ns shadow.spec.util
  (:require [clojure.string :as str]))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))
