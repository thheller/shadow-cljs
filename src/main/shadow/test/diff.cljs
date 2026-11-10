(ns shadow.test.diff
  "structural diffs and a small diff-aware pretty printer for test reports.

   the printer produces segments. a segment is either a string or a [kind string] tuple,
   so the same layout can be rendered as plain text (console, headless) or as HTML (UI).

   uses the same notation matcher-combinators uses, so (is (= ...)) and (is (match? ...))
   failures look the same:

     {:a 1, :b (mismatch (expected 2) (actual 3))}

   matcher-combinators is not required, its mismatch records are recognized by name."
  (:require [clojure.string :as str]))

(defrecord Mismatch [expected actual])
(defrecord Missing [expected])
(defrecord Unexpected [actual])

(defn diff
  "returns actual with every difference to expected replaced by Mismatch/Missing/Unexpected"
  [expected actual]
  (cond
    (= expected actual)
    actual

    (and (map? expected)
         (map? actual)
         (if (record? expected)
           (= (type expected) (type actual))
           (not (record? actual))))
    (let [all-keys
          (-> []
              (into (keys actual))
              (into (remove #(contains? actual %)) (keys expected)))]
      (reduce
        (fn [m k]
          (assoc m k
            (cond
              (not (contains? actual k))
              (->Missing (get expected k))

              (not (contains? expected k))
              (->Unexpected (get actual k))

              :else
              (diff (get expected k) (get actual k)))))
        ;; assoc on a record keeps the record type, so it still prints as one
        (if (record? actual) actual {})
        all-keys))

    (and (sequential? expected) (sequential? actual))
    (let [items
          (loop [e (seq expected)
                 a (seq actual)
                 out []]
            (cond
              (and e a)
              (recur (next e) (next a) (conj out (diff (first e) (first a))))

              e
              (recur (next e) nil (conj out (->Missing (first e))))

              a
              (recur nil (next a) (conj out (->Unexpected (first a))))

              :else
              out))]
      (if (vector? actual)
        items
        (apply list items)))

    (and (set? expected) (set? actual))
    (-> #{}
        (into (filter #(contains? expected %)) actual)
        (into (comp (remove #(contains? actual %)) (map ->Missing)) expected)
        (into (comp (remove #(contains? expected %)) (map ->Unexpected)) actual))

    :else
    (->Mismatch expected actual)))

(defn record-name
  "name of a record, eg. \"matcher-combinators.model.Mismatch\"

   (pr-str (type x)) doesn't work in :advanced builds, but the printer of a record
   contains its name as a string, so that is used instead. never prints more than the name."
  [x]
  (let [s (binding [*print-length* 0
                    *print-level* nil]
            (pr-str x))
        idx (str/index-of s "{")]
    (when (and (str/starts-with? s "#") idx)
      (subs s 1 idx))))

(defn describe-type
  "readable type name, the same in dev and release builds"
  [x]
  (cond
    (nil? x) 'nil
    (record? x) (symbol (record-name x))
    (map? x) 'map
    (vector? x) 'vector
    (set? x) 'set
    (seq? x) 'seq
    (string? x) 'string
    (number? x) 'number
    (keyword? x) 'keyword
    (symbol? x) 'symbol
    (boolean? x) 'boolean
    (fn? x) 'function
    (array? x) 'array
    :else (symbol (pr-str (type x)))))

(defn equality-args
  "returns [expected actual] if the report is for a failed (is (= expected actual))"
  [{:keys [actual] :as report}]
  (when (and (seq? actual)
             (= 'not (first actual)))
    (let [form (second actual)]
      (when (and (seq? form)
                 (= '= (first form))
                 (= 3 (count form)))
        [(nth form 1) (nth form 2)]))))

(defn report-diff
  "returns the diff for a :fail report if possible, nil otherwise"
  [report]
  (cond
    ;; matcher-combinators match? and thrown-match?
    (contains? report :markup)
    (:markup report)

    :else
    (when-some [[expected actual] (equality-args report)]
      (diff expected actual))))

;; printer

(def ^:dynamic *width* 100)
(def ^:dynamic *max-items* 250)

(deftype Marked [kind value])

;; marks truncated collections
(deftype Ellipsis [])
(def ellipsis (Ellipsis.))

(defn- mark [kind value]
  (Marked. kind value))

(defn- mismatch-form [expected actual]
  (list 'mismatch
    (list 'expected (mark :expected expected))
    (list 'actual (mark :actual actual))))

(defn- node->form
  "converts mismatch records into forms that print like they would in matcher-combinators"
  [x]
  (cond
    (not (record? x))
    nil

    (instance? Mismatch x)
    (mismatch-form (:expected x) (:actual x))

    (instance? Missing x)
    (list 'missing (mark :missing (:expected x)))

    (instance? Unexpected x)
    (list 'unexpected (mark :unexpected (:actual x)))

    :else
    (case (record-name x)
      "matcher-combinators.model.Mismatch"
      (mismatch-form (:expected x) (:actual x))

      "matcher-combinators.model.ExpectedMismatch"
      (list 'mismatch
        (mark :expected (symbol "expected mismatch from:"))
        (mark :expected (:expected x))
        (list 'actual (mark :actual (:actual x))))

      "matcher-combinators.model.Missing"
      (list 'missing (mark :missing (:expected x)))

      "matcher-combinators.model.Unexpected"
      (list 'unexpected (mark :unexpected (:actual x)))

      "matcher-combinators.model.TypeMismatch"
      (list 'mismatch
        (list 'expected (mark :expected (describe-type (:expected x))))
        (list 'actual (mark :actual (describe-type (:actual x)))))

      "matcher-combinators.model.InvalidMatcherType"
      (list 'invalid-matcher-input
        (mark :expected (:expected-type-msg x))
        (mark :actual (:provided x)))

      "matcher-combinators.model.InvalidMatcherContext"
      (list 'invalid-matcher-context
        (mark :actual (:message x)))

      "matcher-combinators.printer.EllisionMarker"
      '...

      "matcher-combinators.printer.EmptyMarker"
      (symbol "")

      nil)))

(defn- contains-diff? [x]
  (cond
    (instance? Marked x) false
    (some? (node->form x)) true
    (map? x) (some (fn [[k v]] (or (contains-diff? k) (contains-diff? v))) x)
    ;; take, just in case something infinite ends up here
    (coll? x) (some contains-diff? (take 10000 x))
    :else false))

(defn- limit
  "truncates long collections, unless that would hide a mismatch"
  [items]
  (let [tail (drop *max-items* items)]
    (if (or (empty? tail)
            (some contains-diff? (take 10000 tail)))
      items
      (concat (take *max-items* items) [ellipsis]))))

(defn- sorted-entries
  "hash maps have no meaningful order, sorting makes larger maps easier to scan"
  [m]
  (if (or (<= (count m) 8) (sorted? m))
    m
    (let [ks (keys m)]
      (if (or (every? keyword? ks)
              (every? string? ks)
              (every? number? ks)
              (every? symbol? ks))
        (sort-by key m)
        m))))

(defn- coll-parts [x]
  (cond
    (record? x)
    {:open (str "#" (record-name x) "{") :close "}" :entries (limit x)}

    (map? x)
    {:open "{" :close "}" :entries (limit (sorted-entries x))}

    (vector? x)
    {:open "[" :close "]" :items (limit x)}

    (set? x)
    {:open "#{" :close "}" :items (limit x)}

    (seq? x)
    {:open "(" :close ")" :items (limit x)}

    :else
    nil))

(defn- push! [^js segs kind s]
  (.push segs (if kind [kind s] s)))

(defn- leaf-str [x]
  (if (identical? x ellipsis)
    "..."
    (pr-str x)))

(defn- flat!
  "renders x on a single line, returns the remaining budget, negative if it didn't fit"
  [^js segs x budget kind]
  (cond
    (neg? budget)
    budget

    (instance? Marked x)
    (flat! segs (.-value x) budget (.-kind x))

    :else
    (if-some [form (node->form x)]
      (flat! segs form budget kind)
      (if-some [{:keys [open close entries items]} (coll-parts x)]
        (do (push! segs kind open)
            (loop [budget (- budget (count open))
                   todo (seq (or entries items))
                   first? true]
              (cond
                (neg? budget)
                budget

                (nil? todo)
                (do (push! segs kind close)
                    (- budget (count close)))

                :else
                (let [sep (if entries ", " " ")
                      budget
                      (if first?
                        budget
                        (do (push! segs kind sep)
                            (- budget (count sep))))

                      item (first todo)

                      budget
                      (if (and entries (not (identical? item ellipsis)))
                        (let [budget (flat! segs (key item) budget kind)]
                          (push! segs kind " ")
                          (flat! segs (val item) (dec budget) kind))
                        (flat! segs item budget kind))]

                  (recur budget (next todo) false)))))

        (let [s (leaf-str x)]
          (push! segs kind s)
          (- budget (count s)))))))

(defn- newline! [^js segs col]
  (.push segs (str "\n" (.repeat " " col))))

(declare pp!)

(def ^:private short-width 40)

(defn- short-item
  "[segments length] if x fits on a line in short-width chars, nil otherwise"
  [x kind]
  (let [segs #js []
        remaining (flat! segs x short-width kind)]
    (when-not (neg? remaining)
      [segs (- short-width remaining)])))

(defn- pp-broken!
  "renders x over multiple lines starting at col, returns the col after the last char"
  [^js segs x col kind]
  (cond
    (instance? Marked x)
    (pp! segs (.-value x) col (.-kind x))

    :else
    (if-some [form (node->form x)]
      (pp! segs form col kind)
      (if-some [{:keys [open close entries items]} (coll-parts x)]
        (let [inner (+ col (count open))]
          (push! segs kind open)
          (let [end
                (cond
                  entries
                  (loop [todo (seq entries)
                         first? true
                         end inner]
                    (if-not todo
                      end
                      (let [item (first todo)]
                        (when-not first?
                          (push! segs kind ",")
                          (newline! segs inner))
                        (if (identical? item ellipsis)
                          (do (push! segs kind "...")
                              (recur (next todo) false (+ inner 3)))
                          (let [key-end (pp! segs (key item) inner kind)
                                end
                                (if (> (+ key-end 1) (- *width* 20))
                                  (do (newline! segs (+ inner 2))
                                      (pp! segs (val item) (+ inner 2) kind))
                                  (do (push! segs kind " ")
                                      (pp! segs (val item) (inc key-end) kind)))]
                            (recur (next todo) false end))))))

                  ;; code style, args aligned after the head symbol
                  ;; (= {:a 1}
                  ;;    {:a 2})
                  (and (seq? x) (symbol? (first items)) (next items))
                  (let [head (leaf-str (first items))
                        align (+ inner (count head) 1)
                        arg-col (if (> align (- *width* 40)) (+ col 2) align)]
                    (push! segs kind head)
                    (if (= arg-col align)
                      (push! segs kind " ")
                      (newline! segs arg-col))
                    (loop [todo (next items)
                           first? true
                           end arg-col]
                      (if-not todo
                        end
                        (do (when-not first?
                              (newline! segs arg-col))
                            (recur (next todo) false (pp! segs (first todo) arg-col kind))))))

                  ;; fill lines when all items are short, eg. [1 2 (mismatch (expected 3) (actual 4)) 5 ...]
                  (every? #(short-item % kind) items)
                  (loop [todo (seq items)
                         first? true
                         col inner]
                    (if-not todo
                      col
                      (let [[^js item-segs len] (short-item (first todo) kind)
                            col
                            (cond
                              first?
                              col

                              (> (+ col 1 len) *width*)
                              (do (newline! segs inner)
                                  inner)

                              :else
                              (do (push! segs kind " ")
                                  (inc col)))]
                        (dotimes [i (alength item-segs)]
                          (.push segs (aget item-segs i)))
                        (recur (next todo) false (+ col len)))))

                  :else
                  (loop [todo (seq items)
                         first? true
                         end inner]
                    (if-not todo
                      end
                      (do (when-not first?
                            (newline! segs inner))
                          (recur (next todo) false (pp! segs (first todo) inner kind))))))]

            (push! segs kind close)
            (+ end (count close))))

        ;; can't break leaves
        (let [s (leaf-str x)]
          (push! segs kind s)
          (+ col (count s)))))))

(defn- pp! [^js segs x col kind]
  (let [budget (- *width* col)
        tmp #js []
        remaining (flat! tmp x budget kind)]
    (if (neg? remaining)
      (pp-broken! segs x col kind)
      (do (dotimes [i (alength tmp)]
            (.push segs (aget tmp i)))
          (+ col (- budget remaining))))))

(defn- merge-segments
  "joins adjacent segments of the same kind, fewer DOM nodes"
  [^js segs]
  (persistent!
    (reduce
      (fn [out seg]
        (let [n (count out)
              prev (when (pos? n) (nth out (dec n)))]
          (cond
            (and (string? seg) (string? prev))
            (assoc! out (dec n) (str prev seg))

            (and (vector? seg) (vector? prev) (= (first seg) (first prev)))
            (assoc! out (dec n) [(first seg) (str (second prev) (second seg))])

            :else
            (conj! out seg))))
      (transient [])
      segs)))

(defn layout
  "pretty prints x into a vector of segments, either string or [kind string]
   kind is one of :expected :actual :missing :unexpected"
  ([x]
   (layout x 0))
  ([x start-col]
   (let [segs #js []]
     (pp! segs x start-col nil)
     (merge-segments segs))))

(defn segments->text [segs]
  (->> segs
       (map #(if (string? %) % (second %)))
       (apply str)))

(defn pp-str
  "pretty print x as text. continuation lines are indented by start-col"
  ([x]
   (pp-str x 0))
  ([x start-col]
   (segments->text (layout x start-col))))
