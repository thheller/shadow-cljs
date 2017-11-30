(ns shadow.cljs.devtools.errors
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.pprint :refer (pprint)]
            [shadow.build.closure :as closure]
            [shadow.build.api :as build]
            [shadow.build.ns-form :as ns-form]
            [shadow.build :as comp]
            [shadow.build.resolve :as resolve]
            [shadow.cljs.devtools.config :as config]
            [shadow.build.warnings :as w]
            [expound.alpha :as expound]
            [clojure.tools.logging :as log])
  (:import (java.io StringWriter)
           (clojure.lang ExceptionInfo ArityException)))

(declare error-format)

(def ignored-stack-elements
  #{"clojure.lang.RestFn"
    "clojure.lang.AFn"})

(defmulti ex-format
  (fn [w e]
    (class e)))

(defmethod ex-format :default [w e]
  ;; pretty similar to repl/pst
  (.write w (str (-> e class .getSimpleName) ": " (.getMessage e) "\n"))
  (let [stack
        (->> (.getStackTrace e)
             (remove #(contains? ignored-stack-elements (.getClassName %)))
             ;; (take 12) don't limit, 12 is not enough in many cases, filter more intelligently
             (map repl/stack-element-str))]

    (doseq [x stack]
      (.write w (str "\t" x "\n")))

    (when-let [cause (.getCause e)]
      (.write w "Caused by:\n")
      (error-format w cause)
      )))

(defmethod ex-format ArityException [w e]
  (.write w (.getMessage e)))

(defn get-tag [data]
  (or (:tag data)
      (when (= :reader-exception (:type data))
        ::reader-exception)
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
  (.write w (str (str/trim (.getMessage e)) "\n")))

(defmethod ex-data-format ::comp/get-target-fn
  [w e data]
  (write-msg w e)
  (error-format w (.getCause e)))

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

  (.write w (with-out-str
              (expound/printer data)
              #_(s/explain-out (select-keys data [::s/problems])))))

(defmethod ex-data-format ::ns-form/invalid-ns
  [w e {:keys [config] :as data}]
  (.write w "Invalid namespace declaration\n")
  (spec-explain w data))

(defmethod ex-data-format ::comp/config
  [w e {:keys [config] :as data}]
  (.write w "Invalid configuration\n")
  (spec-explain w data))

(defmethod ex-data-format ::resolve/missing-ns
  [w e data]
  (write-msg w e))

(defmethod ex-data-format ::resolve/missing-js
  [w e data]
  (write-msg w e))

(defmethod ex-data-format :shadow.build.classpath/inspect-cljs
  [w e data]
  (write-msg w e)
  (error-format w (.getCause e)))

(defmethod ex-data-format ::reader-exception
  [w e data]
  (write-msg w e))

(defmethod ex-data-format :shadow.build.targets.node-script/main-not-found
  [w e data]
  (write-msg w e))

(defmethod ex-data-format :shadow.build.targets.node-library/export-not-found
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
  (let [c (count errors)]

    (.write w (format "Closure compilation failed with %d errors%n" c))

    (doseq [{:keys [source-name msg line column] :as err}
            (take 5 errors)]
      (doto w
        (.write "--- ")
        (.write source-name)
        (.write ":")
        (.write (str line))
        (.write "\n")
        ;; trim because some msg have newline and some don't
        (.write (str/trim msg))
        (.write "\n")))

    ;; ran into issues where closure produced 370 errors
    ;; which were all basically the same with different source positions
    ;; this just truncates them to remain readable
    (when (> c 5)
      (.write w "--- remaining errors ommitted ...\n")
      )))

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

(defmethod ex-data-format :shadow.build.compiler/compile-cljs
  [w e {:keys [resource-name file url line column source-excerpt] :as data}]

  ;; FIXME: rewrite warnings code to use a writer instead of just print
  ;; use custom class that handles the styling to it can be turned off easily
  (.write w
    (with-out-str
      (println (w/coded-str [:bold :red] (w/sep-line " ERROR " 6)))
      (println " File:"
        (str (or file url resource-name)
             (when (pos-int? line)
               (str ":" line
                    (when (pos-int? column)
                      (str ":" column))))
             ))

      (when source-excerpt
        (w/print-source-excerpt-header data))))

  (->> e (.getCause) (error-format w))
  (.write w "\n")
  (.write w (w/sep-line))
  (.write w "\n")

  (.write w
    (with-out-str
      (if source-excerpt
        (w/print-source-excerpt-footer data)
        (println (w/sep-line))))))

(defmethod ex-data-format :shadow.cljs.util/macro-load
  [w e {:keys [macro-ns] :as data}]

  (.write w (.getMessage e))
  (.write w "\n\nCaused by:\n")
  (->> e (.getCause) (error-format w)))

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
    (try
      (println (error-format e))
      (catch Throwable e
        (log/warn e "failed to format error")
        (println (.getMessage e)))))
  :error)
