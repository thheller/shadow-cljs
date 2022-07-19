(ns shadow.css.analyzer
  (:require [clojure.tools.reader.reader-types :as reader-types]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]))

(defn lookup-alias [index alias-kw]
  (get-in index [:aliases alias-kw]))

(defn lookup-var [index var-kw]
  (case var-kw
    :ui/sm
    "@media (min-width: 640px)"
    :ui/md
    "@media (min-width: 768px)"
    :ui/lg
    "@media (min-width: 1024px)"
    :ui/xl
    "@media (min-width: 1280px)"
    :ui/xxl ;; :ui/2xl invalid keyword
    "@media (min-width: 1536px)"
    nil))

(def plain-numeric-props
  #{:flex :order :flex-shrink :flex-grow})

(defn convert-num-val [index prop num]
  (if (contains? plain-numeric-props prop)
    (str num)
    (or (get-in index [:svc :spacing num])
        (throw
          (ex-info
            (str "invalid numeric value for prop " prop)
            {:prop prop :num num})))))

(defn add-warning [{:keys [current] :as index} warning-type warning-vals]
  (update index :warnings conj (assoc warning-vals :warning warning-type :current current)))

(declare add-part)

(defn add-alias [index alias-kw]
  ;; FIXME: aliases should be allowed to be any other part, and act accordingly
  (let [alias-val (lookup-alias index alias-kw)]
    (if-not alias-val
      (add-warning index ::missing-alias {:alias alias-kw})
      (update-in index [:current :rules] merge alias-val))))

(defn add-passthrough [index s]
  (throw (ex-info "tbd, str passthrough" {:s s})))

(defn add-map [index defs]
  (reduce-kv
    (fn [index prop [val-type val]]
      (case val-type
        :val
        (update-in index [:current :rules] assoc prop val)

        :number
        (assoc-in index [:current :rules prop] (convert-num-val index prop val))

        :string
        (assoc-in index [:current :rules prop] val)

        :concat
        (let [s (->> val
                     (map (fn [part]
                            (if (string? part)
                              part
                              ;; FIXME: validate exists and a string. warn otherwise
                              (lookup-var index part))))
                     (str/join ""))]
          (assoc-in index [:current :rules prop] s))

        :var
        (let [var-value (lookup-var index val)]
          (cond
            (nil? var-value)
            (add-warning index ::missing-var {:var val})

            (and (map? var-value) (= 1 (count var-value)))
            (assoc-in index [:current :rules prop] (first (vals var-value)))

            (string? var-value)
            (assoc-in index [:current :rules prop] var-value)

            (number? var-value)
            (assoc-in index [:current :rules prop] (convert-num-val index prop var-value))

            :else
            (add-warning index ::invalid-map-val {:prop prop :val-type val-type})))))

    index
    defs))

(defn current-to-defs [{:keys [current] :as index}]
  (-> index
      (cond->
        (seq (:rules current))
        (update :defs conj current))
      (dissoc :current)))

(defn make-selector [{:keys [sel] :as item} sub-sel]
  [sub-sel sel])

(defn add-group [{:keys [current] :as index} {:keys [sel parts]}]
  (let [[sel-type sel-val] sel

        sel
        (case sel-type
          :var
          (lookup-var index sel-val)
          :string
          sel-val)]

    (cond
      (not sel)
      (add-warning index ::group-sel-var-not-found {:val sel-val})

      (map? sel)
      (add-warning index ::group-sel-resolved-to-map {:var sel-val :val sel})

      :else
      (-> (reduce add-part
            (assoc index
              :current
              (-> current
                  (assoc :rules {})
                  (cond->
                    (str/starts-with? sel "@")
                    (-> (update :at-rules conj sel)
                        (assoc :sel (:sel current)))

                    (str/index-of sel "&")
                    (assoc :sel (str/replace sel #"&" (:sel current)))
                    )))
            parts)
          (current-to-defs)
          (assoc :current current)))))

(defn add-part [index [part-id part-val]]
  (case part-id
    :alias
    (add-alias index part-val)
    :map
    (add-map index part-val)
    :passthrough
    (add-passthrough index part-val)
    :group
    (add-group index part-val)))

(defn generate-1 [index {:keys [css-id] :as current}]
  (-> (reduce add-part
        (assoc index
          :current
          (-> current
              (dissoc :parts)
              (assoc :rules {} :at-rules [] :sel (str "." css-id))))
        (:parts current))
      (current-to-defs)))

(defn generate-rules [svc class-defs]
  (-> (reduce generate-1 {:svc svc :warnings [] :defs []} class-defs)
      (dissoc :svc)))


;; FIXME: when parsing respect ns forms and support qualified uses
;; this currently only looks for (css ...) calls
;; it doesn't actually look for (shadow.grove/css ...) calls
;; but this is just easier than going full blown analyzer with support for aliases/scoping

(defn find-css-calls [state form]
  (cond
    (and (list? form) (= 'ns (first form)))
    ;; FIXME: parse ns form. only need name, meta, requires. can ignore :import
    (let [[_ ns meta] form]
      (-> state
          (assoc :ns ns)
          (cond->
            (map? meta)
            (assoc :ns-meta meta)
            )))

    (and (list? form) (= 'css (first form)))
    (update state :css conj
      (-> (meta form)
          (dissoc :source)
          (assoc :form form)))

    (map? form)
    (reduce find-css-calls state (vals form))

    (coll? form)
    (reduce find-css-calls state form)

    :else
    state))

(defn find-css-in-source [src]
  ;; shortcut if src doesn't contain any css, faster than actually parsing
  (when (str/index-of src "(css")
    (let [reader (reader-types/source-logging-push-back-reader src)
          eof (Object.)]

      (loop [state
             {:css []
              :ns nil
              :ns-meta {}
              :require-aliases {}
              :requires #{}}]

        (let [form
              (binding
                [reader/*alias-map*
                 (fn [sym]
                   ;; actually does for ::aliases, need to parse ns anyways for metadata
                   'doesnt.matter)

                 reader/*default-data-reader-fn*
                 (fn [tag data]
                   data)]

                (reader/read {:eof eof :read-cond :preserve} reader))]

          (if (identical? form eof)
            state
            (recur (find-css-calls state form))))
        ))))