(ns shadow.cljs.modern
  (:require
    [cljs.analyzer :as ana]
    [cljs.compiler :as comp]
    [cljs.env :as env]
    [clojure.string :as str]
    [clojure.walk :as walk]))

;; just semi-modern for now
;; requires too much work to make it actually modern (let/const vs var, etc)

;; just got tired of not having access to class and deftype being annoying sometimes

;; FIXME: should calculate protocol pmasks ala deftype so fast-path macros support
;; FIXME: should likely generate sane equals/hash impl?
;; I'm fine with identity compare only for now, this isn't meant to replace deftype/defrecord after all

(alter-var-root #'ana/specials conj `defclass* `super*)

(defn find-and-replace-super-call [form]
  (let [res
        (walk/prewalk
          (fn [form]
            (if-not (and (list? form) (= 'super (first form)))
              form
              `(super* ~@(rest form))))
          form)]


    (if (not= form res)
      res
      ;; if super call was not found, add it first
      (cons `(super*) form))))

(defn parse-class [form]
  (loop [classname nil
         fields []
         constructor nil
         extends nil
         protocols []
         protocol nil
         protocol-fns []
         [head & more :as current] form]

    (let [l? (list? head) s? (symbol? head)]

      (cond
        ;; all done
        (not (seq current))
        (-> {:classname classname
             :extends extends
             :constructor constructor
             :fields fields
             :protocols protocols}
            (cond->
              protocol
              (update :protocols conj {:protocol-name protocol :protocol-fns protocol-fns})))

        ;; SomeClass, symbol before any other form
        (and s? (nil? constructor) (empty? fields) (empty? protocols) (nil? extends) (nil? protocol) (nil? classname))
        (recur head fields constructor extends protocols protocol protocol-fns more)

        ;; (field foo default?)
        (and l? (nil? protocol) (= 'field (first head)))
        (let [field-count (count head)
              field-name (nth head 1)]
          (cond
            ;; (field foo)
            (and (= 2 field-count) (simple-symbol? field-name))
            (recur classname (conj fields {:field-form head :field-name field-name}) constructor extends protocols protocol protocol-fns more)

            ;; (field foo some-default)
            ;; FIXME: should restrict some-default to actual values, not sure expressions will work?
            (and (= 3 field-count) (simple-symbol? field-name))
            (recur classname (conj fields {:field-form head :field-name field-name :field-default (nth head 2)}) constructor extends protocols protocol protocol-fns more)
            :else
            (throw (ex-info "invalid field definition" {:form head}))))

        ;; (constructor [foo bar] ...)
        (and l? (nil? protocol) (nil? constructor) (= 'constructor (first head)) (vector? (second head)))
        (recur classname fields head extends protocols protocol protocol-fns more)

        ;; (extends SomeClass)
        (and l? (nil? protocol) (nil? extends) (= 'extends (first head)) (= 2 (count head)) (symbol? (second head)))
        (recur classname fields constructor (second head) protocols protocol protocol-fns more)

        ;; SomeProtocol start when protocol already active, save protocol, repeat current
        (and s? (some? protocol))
        (recur classname fields constructor extends (conj protocols {:protocol-name protocol :protocol-fns protocol-fns}) nil [] current)

        ;; SomeProtocol start
        s?
        (recur classname fields constructor extends protocols head [] more)

        ;; (protocol-fn [] ...)
        (and l? protocol)
        (recur classname fields constructor extends protocols protocol
          ;; this is important so that the extend-type code emits a var self__ = this;
          ;; no clue why ::ana/type controls that
          (conj protocol-fns (vary-meta head assoc ::ana/type classname))
          more)

        :else
        (throw (ex-info "invalid defclass form" {:form head}))
        ))))


(defmethod ana/parse `defclass*
  [op env form name opts]
  (let [{:keys [classname extends constructor fields protocols] :as parsed}
        (parse-class (rest form))]

    (assert (symbol? classname) "classname required")

    (let [qualified-name (symbol (-> env :ns :name str) (str classname))
          munged-name (symbol (str/replace (comp/munge qualified-name) "." "$"))

          field-syms (map :field-name fields)

          locals (reduce
                   (fn [m fld]
                     (assoc m fld
                              {:name fld
                               ;; why do these exist?
                               :line (ana/get-line fld env)
                               :column (ana/get-col fld env)
                               :local :field
                               :field true
                               :mutable true ;; always treat all as mutable
                               :tag (-> fld meta :tag)}))
                   {classname {:name qualified-name
                               :tag classname}}
                   field-syms)

          [_ ctor-args & ctor-body] constructor

          _ (assert (pos? (count ctor-args)) "contructor requires at least one argument name for this")

          [this-sym & ctor-args] ctor-args

          _ (assert (symbol? this-sym) "can't destructure first constructur argument")

          ctor-body
          (find-and-replace-super-call ctor-body)

          arg-syms (vec (take (count ctor-args) (repeatedly gensym)))

          ctor-locals
          (reduce-kv
            (fn [locals idx fld]
              ;; FIXME: what should fn args locals look like?
              (assoc locals fld {:name fld}))
            ;; pretty sure thats wrong but works in our favor
            ;; since accessing this before super() is invalid
            ;; and this kinda ensures that
            (assoc locals this-sym {:name (symbol "self__")
                                    :tag classname})
            arg-syms)

          ctor-env
          (assoc env :context :statement
                     :locals ctor-locals
                     ::extends extends
                     ::fields fields)

          ;; lazy way to deal with destructuring
          ctor-form
          `(cljs.core/let [~@(interleave ctor-args arg-syms)]
             ~@ctor-body)

          ctor-ast
          (ana/analyze ctor-env ctor-form)

          extend-ast
          (when (seq protocols)
            (let [extend-env
                  (assoc env :locals locals)

                  extend-form
                  `(cljs.core/extend-type ~classname
                     ~@(->> (for [{:keys [protocol-name protocol-fns]} protocols]
                              (into [protocol-name] protocol-fns))
                            (mapcat identity)))]
              (ana/analyze extend-env extend-form)))

          extends-ast
          (when extends
            (ana/analyze (assoc env :context :expr) extends))]

      (swap! env/*compiler* update-in [::ana/namespaces (-> env :ns :name) :defs classname]
        (fn [m]
          (-> m
              (assoc :name qualified-name
                     :tag 'function
                     :type true
                     :num-fields (count arg-syms)
                     :record false
                     :protocols #{})
              (merge (ana/source-info form env)))))

      (-> {:op ::class :env env :form form
           :qualified-name qualified-name
           :munged-name munged-name
           :classname classname
           :tag 'function
           :children []
           :ctor-args arg-syms
           :ctor ctor-ast}
          (cond->
            extends-ast
            (assoc :extends extends-ast)

            extend-ast
            (assoc :extend extend-ast :children [:ctor :extend]))))))


(defmethod comp/emit* ::class
  [{:keys [env qualified-name munged-name extends extend ctor-args ctor]}]
  (comp/emits (comp/munge qualified-name) " = class ")
  (comp/emits munged-name)
  (when extends
    (comp/emits " extends ")
    (comp/emit extends))
  (comp/emitln " {")

  (comp/emitln "  constructor(" (interpose "," ctor-args) ") {")
  (comp/emit ctor)
  (comp/emitln "  }")
  (comp/emitln "};")

  (when extend
    (comp/emit extend)))

;; this is always added by class* so we know the correct position
;; to initialize arguments since its not allowed to access this before super
(defmethod ana/parse `super*
  [op {::keys [extends fields] :as env} [_ & super-args :as form] name opts]
  {:op ::super
   :form form
   :env env
   :call-super? (some? extends)
   :children [:super-args :field-init]
   :super-args (mapv #(ana/analyze (assoc env :context :expr) %) super-args)
   :field-init
   (ana/analyze env
     `(~'do ~@(->> fields
                   (filter :field-default)
                   (map
                     (fn [{:keys [field-name field-default field-form]}]
                       (with-meta
                         `(~'set! ~field-name ~field-default)
                         (meta field-form))))
                   )))})

(defmethod comp/emit* ::super
  [{:keys [call-super? super-args field-init] :as ast}]
  (when call-super?
    (comp/emitln "super(" (interpose "," super-args) ");"))

  ;; required for direct field accesses here and anywhere in the ctor
  (comp/emitln "var self__ = this;")

  ;; initialize fields with defaults directly after super is called
  (comp/emit field-init))

;; only defclass for now since class expressions don't work with extend-type
;; could use a slightly slimmer version for class that doesn't allow protocols
;; or maybe go through specify or so?
(defmacro defclass [& body]
  `(defclass* ~@body))

