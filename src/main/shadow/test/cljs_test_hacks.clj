(ns shadow.test.cljs-test-hacks
  (:require [cljs.test :as ct]))

;; overriding default cljs.test impl
;; should become a proper patch at some point but this is easier for now

(in-ns 'cljs.test)

;; the problem with these is that it tries to go through do-report
;; which is a regular function trying to work out the source location of fail/pass
;; based on a stacktrace which is unreliable and inaccurate
;; but these are called from a macro so we know the location from the form meta

;; no other changes are intended

(defn assert-predicate
  "Returns generic assertion code for any functional predicate.  The
  'expected' argument to 'report' will contains the original form, the
  'actual' argument will contain the form with all its sub-forms
  evaluated.  If the predicate returns false, the 'actual' form will
  be wrapped in (not...)."
  [msg form]
  (let [args (rest form)
        pred (first form)
        {:keys [file line end-line column end-column]} (meta form)]
    `(let [values# (list ~@args)
           result# (apply ~pred values#)]
       (if result#
         (report
           {:type :pass, :message ~msg,
            :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
            :expected '~form, :actual (cons '~pred values#)})
         (report
           {:type :fail, :message ~msg,
            :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
            :expected '~form, :actual (list '~'not (cons '~pred values#))}))
       result#)))

(defn assert-any
  "Returns generic assertion code for any test, including macros, Java
  method calls, or isolated symbols."
  [msg form]
  (let [{:keys [file line end-line column end-column]} (meta form)]
    `(let [value# ~form]
       (if value#
         (report
           {:type :pass, :message ~msg,
            :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
            :expected '~form, :actual value#})
         (report
           {:type :fail, :message ~msg,
            :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
            :expected '~form, :actual value#}))
       value#)))

(defmethod assert-expr :always-fail [menv msg form]
  ;; nil test: always fail
  (let [{:keys [file line end-line column end-column]} (meta form)]
    `(report {:type :fail, :message ~msg
              :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column})))

(defmethod assert-expr 'instance? [menv msg form]
  ;; Test if x is an instance of y.
  (let [{:keys [file line end-line column end-column]} (meta form)]
    `(let [klass# ~(nth form 1)
           object# ~(nth form 2)]
       (let [result# (instance? klass# object#)]
         (if result#
           (report
             {:type :pass, :message ~msg,
              :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
              :expected '~form, :actual (type object#)})
           (report
             {:type :fail, :message ~msg,
              :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
              :expected '~form, :actual (type object#)}))
         result#))))

(defmethod assert-expr 'thrown? [menv msg form]
  ;; (is (thrown? c expr))
  ;; Asserts that evaluating expr throws an exception of class c.
  ;; Returns the exception thrown.
  (let [{:keys [file line end-line column end-column]} (meta form)
        klass (second form)
        body (nthnext form 2)]
    `(try
       ~@body
       (report
         {:type :fail, :message ~msg,
          :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
          :expected '~form, :actual nil})
       (catch ~klass e#
         (report
           {:type :pass, :message ~msg,
            :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
            :expected '~form, :actual e#})
         e#))))

(defmethod assert-expr 'thrown-with-msg? [menv msg form]
  ;; (is (thrown-with-msg? c re expr))
  ;; Asserts that evaluating expr throws an exception of class c.
  ;; Also asserts that the message string of the exception matches
  ;; (with re-find) the regular expression re.
  (let [{:keys [file line end-line column end-column]} (meta form)
        klass (nth form 1)
        re (nth form 2)
        body (nthnext form 3)]
    `(try
       ~@body
       (report {:type :fail, :message ~msg, :expected '~form, :actual nil
                :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column})
       (catch ~klass e#
         (let [m# (.-message e#)]
           (if (re-find ~re m#)
             (report
               {:type :pass, :message ~msg,
                :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
                :expected '~form, :actual e#})
             (report
               {:type :fail, :message ~msg,
                :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
                :expected '~form, :actual e#}))
           e#)))))

(defmacro try-expr
  "Used by the 'is' macro to catch unexpected exceptions.
  You don't call this."
  [msg form]
  (let [{:keys [file line end-line column end-column]} (meta form)]
    `(try
       ~(assert-expr &env msg form)
       (catch :default t#
         (report
           {:type :error, :message ~msg,
            :file ~file :line ~line :end-line ~end-line :column ~column :end-column ~end-column
            :expected '~form, :actual t#})))))