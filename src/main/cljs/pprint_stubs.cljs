(ns cljs.pprint-stubs
  (:refer-clojure :exclude [deftype print println pr prn float?]))

(defn float?
  "Returns true if n is an float."
  [n]
  (and (number? n)
       (not ^boolean (js/isNaN n))
       (not (identical? n js/Infinity))
       (not (== (js/parseFloat n) (js/parseInt n 10)))))

(defn char-code
  "Convert char to int"
  [c]
  (cond
    (number? c) c
    (and (string? c) (== (.-length c) 1)) (.charCodeAt c 0)
    :else (throw (js/Error. "Argument to char must be a character or number"))))

;; *print-length*, *print-level*, *print-namespace-maps* and *print-dup* are defined in cljs.core
(def ^:dynamic
  ^{:doc "Bind to true if you want write to use pretty printing"}
  *print-pretty* true)

(defonce ^:dynamic
  ^{:doc "The pretty print dispatch function. Use with-pprint-dispatch or
set-pprint-dispatch to modify."
    :added "1.2"}
  *print-pprint-dispatch* nil)

(def ^:dynamic
  ^{:doc "Pretty printing will try to avoid anything going beyond this column.
Set it to nil to have pprint let the line be arbitrarily long. This will ignore all
non-mandatory newlines.",
    :added "1.2"}
  *print-right-margin* 72)

(def ^:dynamic
  ^{:doc "The column at which to enter miser style. Depending on the dispatch table,
miser style add newlines in more places to try to keep lines short allowing for further
levels of nesting.",
    :added "1.2"}
  *print-miser-width* 40)

;;; TODO implement output limiting
(def ^:dynamic
  ^{:private true,
    :doc "Maximum number of lines to print in a pretty print instance (N.B. This is not yet used)"}
  *print-lines* nil)

;;; TODO: implement circle and shared
(def ^:dynamic
  ^{:private true,
    :doc "Mark circular structures (N.B. This is not yet used)"}
  *print-circle* nil)

;;; TODO: should we just use *print-dup* here?
(def ^:dynamic
  ^{:private true,
    :doc "Mark repeated structures rather than repeat them (N.B. This is not yet used)"}
  *print-shared* nil)

(def ^:dynamic
  ^{:doc "Don't print namespaces with symbols. This is particularly useful when
pretty printing the results of macro expansions"
    :added "1.2"}
  *print-suppress-namespaces* nil)

;;; TODO: support print-base and print-radix in cl-format
;;; TODO: support print-base and print-radix in rationals
(def ^:dynamic
  ^{:doc "Print a radix specifier in front of integers and rationals. If *print-base* is 2, 8,
or 16, then the radix specifier used is #b, #o, or #x, respectively. Otherwise the
radix specifier is in the form #XXr where XX is the decimal value of *print-base* "
    :added "1.2"}
  *print-radix* nil)

(def ^:dynamic
  ^{:doc "The base to use for printing integers and rationals."
    :added "1.2"}
  *print-base* 10)

(defn write-out [object])

(defn write [object & kw-args])

(defn pprint
  ([object])
  ([object writer]))

(defn set-pprint-dispatch
  [function]
  (set! *print-pprint-dispatch* function)
  nil)

(defn print-table
  ([ks rows])
  ([rows]))
