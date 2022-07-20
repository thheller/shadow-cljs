(ns shadow.css.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::alias keyword?)

(s/def ::str-part
  ;; str or alias
  #(or (string? %) (keyword? %)))

(s/def ::str-concat
  (s/coll-of ::str-part :kind list?))

(s/def ::val
  (s/or
    :string string?
    :number number?
    :alias ::alias
    :concat ::str-concat))

(s/def ::passthrough
  string?)

(s/def ::map
  (s/map-of simple-keyword? ::val))

(defn sub-selector? [x]
  (and (string? x)
       (or (str/starts-with? x "@")
           (str/index-of x "&"))))

(s/def ::group-selector
  (s/or
    :string
    sub-selector?
    :alias
    ::alias
    ))

(s/def ::group
  (s/and
    vector?
    (s/cat
      :sel
      ::group-selector
      :parts
      (s/+ ::part))))

(s/def ::part
  (s/or
    :alias
    ::alias

    :map
    ::map

    :group
    ::group))

(s/def ::root-part
  (s/or
    :alias
    ::alias

    :passthrough
    ::passthrough

    :map
    ::map

    :group
    ::group))

(s/def ::class-def
  (s/cat
    :parts
    (s/+ ::root-part)))

(defn conform! [[_ & body :as form]]
  (let [conformed (s/conform ::class-def body)]
    (when (= conformed ::s/invalid)
      (throw (ex-info "failed to parse class definition"
               (assoc (s/explain-data ::class-def body)
                 :tag ::invalid-class-def
                 :input body))))
    conformed))

(defn conform [[_ & body :as form]]
  (let [conformed (s/conform ::class-def body)]
    (if (= conformed ::s/invalid)
      {:parts [] :invalid true :body body :spec (s/explain-data ::class-def body)}
      conformed)))

(defn generate-id [ns line column]
  (str (-> (str ns)
           (str/replace #"\." "_")
           (munge))
       "__"
       "L" line
       "_"
       "C" column))