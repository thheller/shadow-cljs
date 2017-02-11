(ns shadow.devtools.spec.browser
  (:require [clojure.spec :as s]
            [shadow.devtools.spec.util :refer (non-empty-string?)]))

(s/def ::id keyword?)
(s/def ::entries (s/+ symbol?))

(s/def ::public-dir non-empty-string?)
(s/def ::public-path non-empty-string?)

(s/def ::prepend non-empty-string?)
(s/def ::depends-on (s/* keyword?))

(s/def ::module
  (s/keys
    :req-un
    [::entries
     ::depends-on]
    :opt-un
    [::prepend]))

(s/def ::modules
  (s/map-of
    keyword?
    ::module))


