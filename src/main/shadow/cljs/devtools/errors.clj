(ns shadow.cljs.devtools.errors
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.closure :as closure]
            [shadow.cljs.build :as build]
            [shadow.cljs.ns-form :as ns-form]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.warnings :as w])
  (:import (java.io StringWriter)
           (clojure.lang ExceptionInfo)))

(declare error-format)

(def ignored-stack-elements
  #{"clojure.lang.RestFn"
    "clojure.lang.AFn"})

(defn ex-format [w e]
  ;; pretty similar to repl/pst
  (.write w (str (-> e class .getSimpleName) ": " (.getMessage e) "\n"))
  (let [stack
        (->> (.getStackTrace e)
             (remove #(contains? ignored-stack-elements (.getClassName %)))
             (take 12)
             (map repl/stack-element-str))]

    (doseq [x stack]
      (.write w (str "\t" x "\n")))

    (when-let [cause (.getCause e)]
      (.write w "Caused by:\n")
      (error-format w cause)
      )))

(defn get-tag [data]
  (or (:tag data)
      (when (contains? data ::s/problems)
        ::s/problems)))

(defmulti ex-data-format
  (fn [w e data]
    (get-tag data))
  :default ::default)

(defmethod ex-data-format ::default [w e data]
  (doto w
    (.write (.getMessage e))
    (.write "\n")
    (.write (pr-str data))
    (.write "\n"))

  (ex-format w e))

(defn write-msg [w e]
  (.write w (str (.getMessage e) "\n")))

(defmethod ex-data-format ::comp/get-target-fn
  [w e data]
  (write-msg w e)
  (ex-format w (.getCause e)))

(defn spec-explain [w {::s/keys [problems value spec] :as data}]
  ;; FIXME: meh, no idea how to make spec errors easier to read
  #_(doseq [[in problems]
            (group-by :in problems)

            [val problems]
            (group-by :val problems)]

      (.write w (str "Value\n  "))
      (.write w (str (pr-str val)))
      (.write w "\nDid not conform to spec:\n")
      (doseq [{:keys [via path pred reason] :as problem} problems]
        (.write w "spec: ")
        (.write w (pr-str (s/abbrev pred)))
        (.write w "\n")
        ))
  (.write w (with-out-str (s/explain-out (select-keys data [::s/problems])))))

(defmethod ex-data-format ::ns-form/invalid-ns
  [w e {:keys [config] :as data}]
  (.write w "Invalid namespace declaration\n")
  (spec-explain w data))

(defmethod ex-data-format ::comp/config
  [w e {:keys [config] :as data}]
  (.write w "Invalid configuration\n")
  (spec-explain w data))

(defmethod ex-data-format ::build/missing-ns
  [w e data]
  (write-msg w e))

(defmethod ex-data-format ::s/problems
  [w e {::s/keys [problems value spec] :as data}]
  (let [msg (.getMessage e)

        [_ fdef :as x]
        (re-find #"Call to ([^ ]+) did not conform to spec:" msg)]
    ;; macroexpand errors already contain the explain message
    (if-not x
      (.write w (str msg "\n"))
      (.write w (format "Call to %s did not conform to spec\n" fdef)))

    (spec-explain w data)
    ))

(defmethod ex-data-format ::closure/errors
  [w e {:keys [errors] :as data}]
  (.write w "Closure optimization failed:\n")
  (doseq [{:keys [msg] :as err} errors]
    (doto w
      (.write "---\n")
      (.write msg))))

(defmethod ex-data-format ::config/no-build
  [w e {:keys [id] :as data}]
  ;; FIXME: show list of all build ids?
  (.write w (format "No configuration for build \"%s\" found." id)))

(defmethod ex-data-format :cljs/analysis-error
  [w e {:keys [file line column error-type] :as data}]
  ;; FIXME: assumes this info was printed before
  #_(doto w
      (.write "CLJS error in ")
      (.write (or file "<unknown>"))
      (.write " ")
      (.write "at ")
      (.write (str (or line 0)))
      (.write ":")
      (.write (str (or column 0)))
      (.write "\n"))

  (let [cause (.getCause e)]
    (cond
      (keyword? error-type)
      (.write w (:msg data))

      ;; FIXME: this skips printing (.getMessage e) as it seems to be a repetition of the cause most of the time?
      cause
      (error-format w cause)

      :else
      (.write w (.getMessage e))
      )))

(defmethod ex-data-format :shadow.cljs.build/compile-cljs
  [w e {:keys [source-name file url line column source-excerpt] :as data}]

  ;; FIXME: rewrite warnings code to use a writer instead of just print
  ;; use custom class that handles the styling to it can be turned off easily
  (.write w
    (with-out-str
      (println (w/coded-str [:bold :red] (w/sep-line " ERROR " 6)))
      (println " File:"
        (if-not file
          source-name
          (str file
               (when (pos-int? line)
                 (str ":" line
                      (when (pos-int? column)
                        (str ":" column))))
               )))

      (when source-excerpt
        (w/print-source-excerpt-header data))))

  (.write w
    (with-out-str
      (if source-excerpt
        (w/print-source-excerpt-footer data)
        (println (w/sep-line)))))

  (->> e (.getCause) (error-format w))

  (.write w (w/sep-line)))

(defn error-format
  ([e]
   (let [w (StringWriter.)]
     (error-format w e)
     (.toString w)))
  ([w e]
   (when e
     (let [data (ex-data e)]
       (if data
         (ex-data-format w e data)
         (ex-format w e))
       ))))

(defn user-friendly-error [e]
  (binding [*out* *err*]
    (println (error-format e)))
  :error)
