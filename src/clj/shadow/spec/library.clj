(ns shadow.spec.library
  (:require [clojure.spec :as spec]))

(spec/def ::exports
  (spec/map-of
    keyword?
    qualified-symbol?))
