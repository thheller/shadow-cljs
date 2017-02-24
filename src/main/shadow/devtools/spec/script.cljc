(ns shadow.devtools.spec.script
  (:require [clojure.spec :as s]
            [shadow.devtools.spec.util :as util]))

(s/def ::main qualified-symbol?)

(s/def ::output-to util/non-empty-string?)
