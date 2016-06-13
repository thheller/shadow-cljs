(ns shadow.spec.build
  (:require [clojure.spec :as spec]
            [shadow.spec.util :refer (non-empty-string?)]
            [shadow.spec.browser :as s-browser]
            [shadow.spec.script :as s-script]
            [shadow.spec.library :as s-library]
            ))

(spec/def ::id keyword?)
(spec/def ::target #{:browser
                     :script
                     :library})

(defmulti target-spec :target)

(defmethod target-spec :browser [_]
  (spec/keys
    :req-un
    [::s-browser/modules]
    :opt-un
    [
     ::public-dir
     ::public-path]))

(defmethod target-spec :script [_]
  (spec/keys
    :req-un
    [::s-script/main
     ::s-script/output-to]))

(defmethod target-spec :library [_]
  (spec/keys
    :req-un
    [::s-script/output-to
     ::s-library/exports]))

(spec/def ::build
  (spec/and
    (spec/keys
      :req-un
      [::id ::target]
      :opt-un
      [::dev])
    (spec/multi-spec target-spec :target)))





