(ns shadow.css.analyzer
  (:require
    [clojure.string :as str]
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as reader-types]
    ))

(defn reduce-> [init rfn coll]
  (reduce rfn init coll))

(defn lookup-alias [svc alias-kw]
  (get-in svc [:aliases alias-kw]))

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

(defn add-warning [svc form warning-type warning-vals]
  (update form :warnings conj (assoc warning-vals :warning-type warning-type)))

(declare add-part)

(defn add-alias [svc form alias-kw]
  (let [alias-val (lookup-alias svc alias-kw)]
    (cond
      (not alias-val)
      (add-warning svc form ::missing-alias {:alias alias-kw})

      (map? alias-val)
      (add-part svc form alias-val)

      (vector? alias-val)
      (reduce #(add-part svc %1 %2) form alias-val)

      :else
      (add-warning svc form ::invalid-alias-replacement {:alias alias-kw :val alias-val})
      )))

(defn add-map [svc form defs]
  (reduce-kv
    (fn [form prop val]
      (let [[form val]
            (cond
              ;; {:thing "val"}
              (string? val)
              [form val]

              ;; {:thing 4}
              (number? val)
              [form (convert-num-val svc prop val)]

              ;; {:thing :alias}
              (keyword? val)
              (let [alias-value (lookup-alias svc val)]
                (cond
                  (nil? alias-value)
                  [(add-warning svc form ::missing-alias {:alias val})
                   nil]

                  (and (map? alias-value) (contains? alias-value prop))
                  [form (get alias-value prop)]

                  (string? alias-value)
                  [form alias-value]

                  (number? alias-value)
                  [form (convert-num-val form prop alias-value)]

                  :else
                  [(add-warning svc form ::invalid-map-val {:prop prop :val val})
                   nil]
                  )))]

        (assoc-in form [:rules (:sel form) prop] val)))

    form
    defs))

(defn make-sub-rule [{:keys [stack rules] :as form}]
  (update form :sub-rules assoc stack rules))

(defn add-group* [svc form group-sel group-parts]
  (cond
    (not (string? group-sel))
    (add-warning svc form ::invalid-group-sel {:sel group-sel})

    ;; media queries
    (str/starts-with? group-sel "@media")
    (let [{:keys [rules media]} form

          new-media
          (if-not (seq media)
            group-sel

            ;; attempt to combine media queries via and
            ;; FIXME: can all @media queries combined like this?
            ;;   @media (min-width: 300) @media print
            ;; combined to
            ;;   @media (min-width: 300) and print
            ;; so there is only one media rule and no nesting?
            (str media " and " (subs group-sel 7)))]

      (-> form
          (assoc :rules {} :media new-media)
          (reduce-> #(add-part svc %1 %2) group-parts)
          ((fn [{:keys [rules] :as form}]
             (assoc-in form [:at-rules new-media] rules)))
          (assoc :rules rules :media media)))

    (str/index-of group-sel "&")
    (let [{:keys [rules sel]} form]

      (if (not= sel "&")
        (throw (ex-info "tbd, combining &" {:sel sel :group-sel group-sel}))
        (-> form
            (assoc :sel group-sel)
            (reduce-> #(add-part svc %1 %2) group-parts)
            (assoc :sel sel))))

    :else
    (add-warning svc form ::invalid-group-sel {:sel group-sel})))

(defn add-group [svc form [sel & parts]]
  (if (keyword? sel)
    (if-some [alias-value (lookup-alias svc sel)]
      (add-group* svc form alias-value parts)
      (add-warning svc form ::group-sel-alias-not-found {:alias sel}))
    (add-group* svc form sel parts)))

(defn add-part [svc form part]
  (cond
    (string? part) ;; "other-class", passthrough, ignored here, handled in macro
    form

    (keyword? part) ;; :px-4 alias
    (add-alias svc form part)

    (map? part) ;; {:padding 4}
    (add-map svc form part)

    (vector? part) ;; ["&:hover" :px-4] subgroup
    (add-group svc form part)

    :else
    (add-warning svc form ::invalid-part part)))

(defn process-form [svc {:keys [form] :as form-info}]
  (-> form-info
      (assoc :sel "&" :media nil :rules {} :at-rules {} :warnings [])
      (reduce-> #(add-part svc %1 %2) (rest form))
      (dissoc :stack :sel :media)))

;; FIXME: when parsing respect ns forms and support qualified uses
;; this currently only looks for (css ...) calls
;; it doesn't actually look for (shadow.grove/css ...) calls
;; but this is just easier than going full blown analyzer with support for aliases/scoping

(defn find-css-calls [state form]
  (cond
    (map? form)
    (reduce find-css-calls state (vals form))

    (list? form)
    (case (first form)
      ;; (ns foo {:maybe "meta") ...)
      ;; FIXME: parse ns form. only need name, meta, requires. can ignore :import
      ns
      (let [[_ ns meta] form]
        (-> state
            (assoc :ns ns)
            (cond->
              (map? meta)
              (assoc :ns-meta meta)
              )))

      ;; don't traverse into (comment ...)
      comment
      state

      ;; thing we actually look for
      css
      (update state :css conj
        (-> (meta form)
            (dissoc :source)
            (assoc :form form)))

      ;; any other list
      (reduce find-css-calls state form))

    ;; sets, vectors
    (coll? form)
    (reduce find-css-calls state form)

    :else
    state))

(defn find-css-in-source [src]
  ;; shortcut if src doesn't contain any css, faster than parsing all forms
  (let [has-css? (str/index-of src "(css")

        reader (reader-types/source-logging-push-back-reader src)
        eof (Object.)]

    (loop [state
           {:css []
            :ns nil
            :ns-meta {}
            :require-aliases {}
            :requires []}]

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

        (cond
          (identical? form eof)
          state

          ;; stops after ns form if no (css was present in source
          (and (:ns state) (not has-css?))
          state

          :else
          (recur (find-css-calls state form))))
      )))