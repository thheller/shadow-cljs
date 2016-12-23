(ns shadow.spec.script
  (:require [clojure.spec :as spec]
            [shadow.spec.util :as util]))

(spec/def ::main qualified-symbol?)

(spec/def ::output-to util/non-empty-string?)
