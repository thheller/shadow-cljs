(ns shadow.cljs.cljs-specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::file-min string?)

(s/def ::file string?)

;; probably vector or set, doesn't matter
(s/def ::externs (s/coll-of string? :distinct true))
(s/def ::provides (s/coll-of string? :distinct true))
(s/def ::requires (s/coll-of string? :distinct true))

(s/def ::foreign-lib
  (s/keys
    :opt-un
    [::file-min
     ::file
     ::externs
     ::provides
     ::requires]))

(s/def ::foreign-libs
  (s/coll-of ::foreign-lib :distinct true))

(s/def ::deps-cljs
  (s/keys
    :opt-un
    [::externs
     ::foreign-libs]))
