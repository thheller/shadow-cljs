(ns shadow.build.config
  (:require [clojure.spec.alpha :as s]))

(s/def ::build-id keyword?)

(s/def ::target
  #(or (simple-keyword? %)
       (symbol? %)))

(s/def ::build-hook
  (s/cat
    :hook-sym qualified-symbol?
    :hook-args (s/* any?)
    ))

(s/def ::build-hooks
  (s/coll-of ::build-hook :kind vector?))

(defmulti target-spec :target :default ::default)

(defmethod target-spec ::default [_]
  (s/spec any?))

(s/def ::build
  (s/keys
    :req-un
    [::build-id
     ::target]
    :opt-un
    [::build-hooks]))

(s/def ::build+target
  (s/and
    ::build
    (s/multi-spec target-spec :target)))

;; deps.cljs specs

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