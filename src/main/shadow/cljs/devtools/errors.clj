(ns shadow.cljs.devtools.errors
  (:require [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.spec :as s]
            [clojure.pprint :refer (pprint)])
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
    (.write "FIXME: error format for ")
    (.write (-> e type (.getName)))
    (.write " ")
    (.write (str (or (get-tag data) "<:tag missing>")))
    (.write "\n")
    (.write (.getMessage e))
    (.write "\n"))

  (throw e))

(defmethod ex-data-format ::s/problems
  [w e {::s/keys [problems value] :as data}]
  (.write w (.getMessage e))
  (.write w "== Spec\n")
  (.write w
    (with-out-str
      (-> data
          (dissoc ::s/args)
          (s/explain-out)
          )))
  (.write w "==\n"))

(defmethod ex-data-format :shadow.cljs.build/closure
  [w e {:keys [errors] :as data}]
  (.write w "Closure optimization failed:\n")
  (doseq [{:keys [msg] :as err} errors]
    (doto w
      (.write "---\n")
      (.write msg))))

(defmethod ex-data-format :shadow.cljs.devtools.compiler/config
  [w e {:keys [config] :as data}]
  (.write w "Invalid configuration\n")
  (.write w (with-out-str (s/explain-out data))))

(defmethod ex-data-format :cljs/analysis-error
  [w e {:keys [file line column] :as data}]
  (doto w
    (.write "CLJS error in ")
    (.write (or file "<unknown>"))
    (.write " ")
    (.write "at ")
    (.write (str (or line 0)))
    (.write ":")
    (.write (str (or column 0)))
    (.write "\n"))

  (error-format w (.getCause e)))

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
