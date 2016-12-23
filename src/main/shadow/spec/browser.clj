(ns shadow.spec.browser
  (:require [clojure.spec :as spec]
            [shadow.spec.util :refer (non-empty-string?)]))

(spec/def ::id keyword?)
(spec/def ::entries (spec/+ symbol?))

(spec/def ::public-dir non-empty-string?)
(spec/def ::public-path non-empty-string?)

(spec/def ::prepend non-empty-string?)
(spec/def ::depends-on (spec/* keyword?))

(spec/def ::module
  (spec/keys
    :req-un
    [::entries
     ::depends-on]
    :opt-un
    [::prepend]))

(spec/def ::modules
  (spec/map-of
    keyword?
    ::module))


