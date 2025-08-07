(ns shadow.cljs.devtools.errors
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [shadow.build.closure :as closure]
            [shadow.build.api :as build]
            [shadow.build.ns-form :as ns-form]
            [shadow.build :as comp]
            [shadow.build.resolve :as resolve]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.config :as config]
            [shadow.build.warnings :as w]
            [shadow.jvm-log :as log]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io])
  (:import (java.io StringWriter FileNotFoundException)
           (clojure.lang ArityException)))

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
             ;; limiting this is not ideal but we don't want to dump large stacks on the user
             ;; especially when analysis errors are really long but provide no useful insight
             (take 80)
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
      (and (:clojure.error/phase data)
           ::error-detail)
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
  [w e {:keys [target build-id] :as data}]

  (if (instance? FileNotFoundException (.getCause e))
    ;; misspelled target
    (.write w (str "Target \"" (pr-str target) "\" for build " build-id " was not found. The built-in targets are:\n"
                   (->> [:browser
                         :browser-test
                         :node-script
                         :node-library
                         :npm-module
                         :karma
                         :bootstrap]
                        (map #(str "  - " %))
                        (str/join "\n"))))


    ;; print any other exception as is
    (do (write-msg w e)
        (error-format w (.getCause e)))
    ))

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

  (locking comp/target-require-lock
    (require 'expound.alpha)

    (let [printer (resolve 'expound.alpha/printer)]
      (.write w (with-out-str
                  (printer data)
                  #_(s/explain-out (select-keys data [::s/problems])))))))

(defmethod ex-data-format ::ns-form/invalid-ns
  [w e {:keys [config] :as data}]
  (.write w "Invalid namespace declaration\n")
  (spec-explain w data))

(defmethod ex-data-format ::comp/config
  [w e {:keys [config] :as data}]
  (.write w "Invalid configuration\n")
  (spec-explain w data))

(defmethod ex-data-format ::comp/source-paths
  [w e {:keys [config] :as data}]
  (.write w (format ":source-paths is only valid globally and cannot be configured in build %s." (:build-id config))))

(defmethod ex-data-format ::resolve/missing-ns
  [w e {:keys [require] :as data}]
  (write-msg w e)

  (let [filename (str (util/ns->path require) ".clj")]
    (when-let [rc (io/resource filename)]
      (if (= "file" (.getProtocol rc))
        (.write w (str "\"" filename "\" was found on the classpath. Should this be a .cljs file?\n"))
        (.write w (str "\"" filename "\" was found on the classpath. Maybe this library only supports CLJ?")))
      )))

(defmethod ex-data-format ::resolve/missing-js
  [w e {:keys [require resolved-stack js-package-dirs] :as data}]
  (write-msg w e)

  (when (seq resolved-stack)
    (.write w
      (str "\nDependency Trace:\n"
           (->> resolved-stack
                (map #(str "\t" %))
                (str/join "\n"))
           "\n")))

  (when (util/is-package-require? require)
    (.write w (str "\n"
                   "Searched for npm packages in:\n"
                   (->> (for [module-dir js-package-dirs]
                          (str "\t" (.getAbsolutePath module-dir)))
                        (str/join "\n"))
                   "\n"
                   "\n"
                   "See: https://shadow-cljs.github.io/docs/UsersGuide.html#npm-install\n"
                   ))))

(defmethod ex-data-format :shadow.build.compiler/fail-many
  [w e {:keys [errors] :as data}]
  (.write w "Multiple files failed to compile.\n")
  (doseq [e (vals errors)]
    (error-format w e)))

(defmethod ex-data-format ::resolve/circular-dependency
  [w e data]
  (write-msg w e))

(defmethod ex-data-format :shadow.build.classpath/inspect-cljs
  [w e data]
  (write-msg w e)
  (error-format w (.getCause e)))

(defmethod ex-data-format :shadow.build.npm/file-info-errors
  [w e {:keys [file info] :as data}]
  (.write w "Errors encountered while trying to parse file\n  ")
  (.write w (.getAbsolutePath file))
  (doseq [js-error (:js-errors info)]
    (.write w (str "\n  " (pr-str js-error))))

  (when-some [cause (.getCause e)]
    (.write w "\n\n")
    (error-format w cause)))

(defmethod ex-data-format :shadow.build.npm/file-info-failed
  [w e {:keys [file require-from] :as data}]
  (.write w "Failed to inspect file\n  ")
  (.write w (.getAbsolutePath file))
  (when require-from
    (.write w "\n\nit was required from\n  ")
    (.write w (.getAbsolutePath (:file require-from))))
  (.write w "\n\n")
  (error-format w (.getCause e)))

(defmethod ex-data-format :shadow.build/hook-error
  [w e data]
  (write-msg w e)
  (error-format w (.getCause e)))

(defmethod ex-data-format :shadow.build.classpath/access-outside-classpath
  [w e data]
  (write-msg w e))

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

(defmethod ex-data-format ::error-detail
  [w e {:clojure.error/keys [phase symbol]}]

  (let [msg
        (case phase
          :macro-syntax-check
          (format "Syntax error macroexpanding%s.%n" (if symbol (str " " symbol) ""))

          :macroexpansion
          (format "Encountered error when macroexpanding%s.%n"
            (if symbol (str " " symbol) ""))

          :compilation
          (format "%s%n" (.getMessage e))

          (format "Error in phase %s%n" phase))]

    (.write w msg)

    (when-some [cause (.getCause e)]
      (error-format w cause))))

(defmethod ex-data-format :shadow.build.compiler/emit-ex
  [w e {:keys [errors] :as data}]
  (.write w "An error occurred while generating code for the form.\n")
  (let [cause (.getCause e)]
    (ex-format w cause)))

(defmethod ex-data-format ::closure/errors
  [w e {:keys [errors] :as data}]
  (let [c (count errors)]

    (.write w (format "Closure compilation failed with %d errors%n" c))

    (doseq [{:keys [source-name msg line column] :as err}
            errors #_(take 5 errors)]
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
    #_(when (> c 5)
        (.write w "--- remaining errors ommitted ...\n")
        )))

(defmethod ex-data-format ::closure/load-externs-failed
  [w e {:keys [errors] :as data}]
  (.write w (.getMessage e)))

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

  (when-let [cause (.getCause e)]
    (error-format w cause))
  (.write w "\n")
  (.write w (w/sep-line))
  (.write w "\n")

  (.write w
    (with-out-str
      (if source-excerpt
        (w/print-source-excerpt-footer data)
        (println (w/sep-line))))))

(defmethod ex-data-format :shadow.build.compiler/js-error
  [w e {:keys [file js-errors] :as data}]

  (doseq [{:keys [line column message source-excerpt] :as err} js-errors]
    ;; FIXME: rewrite warnings code to use a writer instead of just print
    ;; use custom class that handles the styling to it can be turned off easily
    (.write w
      (with-out-str
        (println (w/coded-str [:bold :red] (w/sep-line " ERROR " 6)))
        (println " File:"
          (str file
               (when (pos-int? line)
                 (str ":" line
                      (when (pos-int? column)
                        (str ":" column))))))

        (when source-excerpt
          (w/print-source-excerpt-header err))))

    (.write w message)
    (.write w "\n")
    (.write w (w/sep-line))
    (.write w "\n")

    (.write w
      (with-out-str
        (if source-excerpt
          (w/print-source-excerpt-footer err)
          (println (w/sep-line)))))))

(defmethod ex-data-format :shadow.cljs.repl/process-ex
  [w e {:keys [source] :as data}]

  (.write w (w/coded-str [:bold :red] (w/sep-line " REPL Error while processing " 6)))
  (.write w "\n")
  (.write w source)
  (.write w "\n")

  (error-format w (.getCause e)))

(defmethod ex-data-format :shadow.build.modules/module-entry-moved
  [w e {:keys [entry expected moved-to] :as data}]

  (.write w (.getMessage e)))

(defmethod ex-data-format :shadow.build.ns-form/require-conflict
  [w e data]
  (.write w (.getMessage e)))

(defmethod ex-data-format :shadow.build.macros/macro-load
  [w e data]
  (write-msg w e)
  (error-format w (.getCause e)))

(defmethod ex-data-format :shadow.build.resolve/unexpected-ns
  [w e {:keys [resource actual-ns expected-ns]}]
  (.write w (.getMessage e))
  (.write w "\n")
  (.write w "Resource: ")
  (.write w resource)
  (.write w "\n")
  (.write w "Expected: ")
  (.write w (pr-str expected-ns))
  (.write w "\n")
  (.write w "Actual: ")
  (.write w (pr-str actual-ns))
  (.write w "\n"))

(defmethod ex-data-format :shadow.build.resolve/require-mismatch
  [w e data]
  (.write w (.getMessage e)))


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
        (log/warn-ex e ::format-error)
        (println (.getMessage e)))))
  :error)
