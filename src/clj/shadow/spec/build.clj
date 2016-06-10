(ns shadow.spec.build
  (:require [clojure.spec :as spec]
            [shadow.spec.util :refer (non-empty-string?)]
            [shadow.spec.module :as s-module]))

(spec/def ::id keyword?)
(spec/def ::target #{:browser})

(spec/def ::public-dir non-empty-string?)
(spec/def ::public-path non-empty-string?)

(spec/def ::build
  (spec/keys
    :req-un
    [::id
     ::target
     ::s-module/modules]
    :opt-un
    [::dev
     ::public-dir
     ::public-path]))





