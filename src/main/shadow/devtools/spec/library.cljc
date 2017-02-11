(ns shadow.devtools.spec.library
  (:require [clojure.spec :as s]))

(s/def ::exports
  (s/map-of
    keyword?
    qualified-symbol?))
