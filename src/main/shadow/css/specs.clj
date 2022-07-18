(ns shadow.css.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::alias
  simple-keyword?)

(s/def ::var
  qualified-keyword?)

(s/def ::str-part
  ;; str or var
  #(or (string? %) (qualified-keyword? %)))

(s/def ::str-concat
  (s/coll-of ::str-part :kind list?))

(s/def ::val
  (s/or
    :string string?
    :number number?
    :var ::var
    :concat ::str-concat))

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
    :var
    ::var
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

    :var
    ::var

    :map
    ::map

    :group
    ::group))

(s/def ::class-def
  (s/cat
    :parts
    (s/+ ::part)))

(defn conform! [body]
  (let [conformed (s/conform ::class-def body)]
    (when (= conformed ::s/invalid)
      (throw (ex-info "failed to parse class definition"
               (assoc (s/explain-data ::class-def body)
                 :tag ::invalid-class-def
                 :input body))))
    conformed))

(defn conform [body]
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

(comment
  {:color/primary {:color "red"}
   :color/secondary {:color "green"}}
  [:div {:class
         (css :px-4 :my-2 :color/primary
           [:ui/md :px-6]
           [:ui/lg :px-8]
           ["&:hover" :color/secondary])}]

  (conform!
    '[:px-4 :my-2 :color/primary
      [:ui/md :px-6]
      [:ui/lg :px-8]
      ["&:hover" :color/secondary]])
  )