(ns shadow.devtools.targets.shared
  (:require [clojure.spec :as s]
            [clojure.string :as str]))

(defn non-empty-string? [x]
  (and (string? x)
       (not (str/blank? x))))

(s/def ::public-dir non-empty-string?)

(s/def ::output-to non-empty-string?)


