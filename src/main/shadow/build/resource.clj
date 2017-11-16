(ns shadow.build.resource
  (:refer-clojure :exclude (assert))
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [shadow.spec :as ss]
            [shadow.cljs.util :as util])
  (:import (java.io File)
           (java.net URL)))

;; this namespace is also the primary keyword alias for resources

(s/def ::resource-id
  (s/and vector?
    (s/cat :kw qualified-keyword? :extra (s/+ any?))))

(s/def ::resource-name string?)
(s/def ::output-name string?)

(s/def ::type #{:cljs :goog :js :shadow-js})

(s/def ::file util/is-absolute-file?)
(s/def ::url #(instance? URL %))

(s/def ::provide simple-symbol?)
(s/def ::provides (s/coll-of ::provide :kind set?))

(s/def ::require simple-symbol?)
(s/def ::requires (s/coll-of ::require :kind set?))

(s/def ::macro-requires ::requires)

(s/def ::module-type string?)

(s/def ::sym-or-str
  (s/or :sym simple-symbol?
        :str string?))

(s/def ::deps (s/coll-of ::sym-or-str :kind vector?))

;; this is required for output generation since it needs to skip
;; output if the file already exists for incremental compiles
;; this does not use cache-key as there is only the file to look at
;; and it should not need to parse the file
(s/def ::last-modified nat-int?)

;; this is more reliable to use for cljs caching since some inputs
;; may have more than one files they reference (ie. :foreign)
;; they must invalidate the cache for every entry not just one
(s/def ::cache-key some?)

(s/def ::resource
  (s/keys
    :req-un
    ;; I'm not using qualified keywords in the resource map
    ;; because {::keys [name] :as rc} still conflicts with clojure.core/name
    ;; I frequently run into issues with that
    ;; also there is :module-id and :module-name which destructure
    ;; to id or name as well so it is never clear what id or name mean
    ;; should have done that a long time ago, probably also for type ...
    [::resource-id
     ::resource-name
     ::output-name
     ::type
     ::cache-key
     ::last-modified]

    :opt-un
    [::url
     ::file
     ::provides
     ::macro-requires
     ::requires
     ::deps]
    ))

(defn valid-resource? [{:keys [resource-id] :as rc}]
  ;; this is for asserts but the default error is basically useless
  ;; java.lang.AssertionError: Assert failed: (rc/valid-resource? src)
  ;; so it just explains since its for debugging anyways
  (or (s/valid? ::resource rc)
      (do (s/explain ::resource rc)
          false)))

(defn valid-resource-id? [id]
  (s/valid? ::resource-id id))

(s/def ::js string?)

(s/def ::source-map map?)
(s/def ::source-map-json string?)

(s/def ::output
  (s/keys
    :req-un
    [::resource-id]
    :opt-un
    [::js
     ::source-map
     ::source-map-json]
    ))

(defn valid-output? [rc]
  (s/valid? ::output rc))

(defn assert [rc]
  (s/assert ::resource rc))

(defn explain [rc]
  (s/explain ::resource rc))

;; windows filenames need to be normalized because they contain backslashes which browsers don't understand
(def normalize-name
  (if (= File/separatorChar \/)
    identity
    (fn [^String name]
      (str/replace name File/separatorChar \/))))


