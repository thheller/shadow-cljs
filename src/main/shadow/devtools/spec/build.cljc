(ns shadow.devtools.spec.build
  (:require [clojure.spec :as s]
            [shadow.devtools.spec.util :refer (non-empty-string?)]
            [shadow.devtools.spec.browser :as s-browser]
            [shadow.devtools.spec.script :as s-script]
            [shadow.devtools.spec.library :as s-library]
            ))

(s/def ::id
  keyword?)

(s/def ::target
  #{:browser
    :script
    :library})

(defmulti target-spec :target)

(defmethod target-spec :browser [_]
  (s/keys
    :req-un
    [::s-browser/modules]
    :opt-un
    [
     ::public-dir
     ::public-path]))

(defmethod target-spec :script [_]
  (s/keys
    :req-un
    [::s-script/main
     ::s-script/output-to]))

(defmethod target-spec :library [_]
  (s/keys
    :req-un
    [::s-script/output-to
     ::s-library/exports]))

(s/def ::build
  (s/and
    (s/keys
      :req-un
      [::id ::target]
      :opt-un
      [::dev])
    (s/multi-spec target-spec :target)))
