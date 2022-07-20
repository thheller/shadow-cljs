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
    (if-not alias-val
      (add-warning svc form ::missing-alias {:alias alias-kw})
      (add-part svc form alias-val))))

(defn add-map [svc form defs]
  (reduce-kv
    (fn [form prop val]
      (cond
        ;; {:thing "val"}
        (string? val)
        (assoc-in form [:rules prop] val)

        ;; {:thing 4}
        (number? val)
        (assoc-in form [:rules prop] (convert-num-val svc prop val))

        ;; {:thing :alias}
        (keyword? val)
        (let [alias-value (lookup-alias svc val)]
          (cond
            (nil? alias-value)
            (add-warning svc form ::missing-alias {:alias val})

            (and (map? alias-value) (contains? alias-value prop))
            (assoc-in form [:rules prop] (get alias-value prop))

            (string? alias-value)
            (assoc-in form [:rules prop] alias-value)

            (number? alias-value)
            (assoc-in form [:rules prop] (convert-num-val form prop alias-value))

            :else
            (add-warning svc form ::invalid-map-val {:prop prop :val val})))

        #_#_:concat
                (let [s (->> val
                             (map (fn [part]
                                    (if (string? part)
                                      part
                                      ;; FIXME: validate exists and a string. warn otherwise
                                      (lookup-alias form part))))
                             (str/join ""))]
                  (assoc-in form [:current :rules prop] s))
        ))

    form
    defs))

(defn make-sub-rule [{:keys [stack rules] :as form}]
  (update form :sub-rules assoc stack rules))

(defn add-group* [svc form sel parts]
  (let [{:keys [stack rules]} form]
    (-> form
        (assoc :rules {} :stack (conj stack sel))
        (reduce-> #(add-part svc %1 %2) parts)
        (make-sub-rule)
        (assoc :stack stack :rules rules))))

(defn add-group [svc form [sel & parts]]
  (cond
    (keyword? sel)
    (if-some [alias-value (lookup-alias svc sel)]
      (add-group* svc form alias-value parts)
      (add-warning svc form ::group-sel-alias-not-found {:alias sel}))

    (not (string? sel))
    (add-warning svc form ::invalid-group-sel {:sel sel})

    :else
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
  (-> (reduce
        #(add-part svc %1 %2)
        (assoc form-info :rules {} :stack [] :sub-rules {} :warnings [])
        (rest form))
      (dissoc :stack)))

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