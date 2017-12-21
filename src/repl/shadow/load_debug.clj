(ns shadow.load-debug)

;; hacks to figure out how long loading a namespace takes


(in-ns 'clojure.core)

(def shadow-load-ref (atom {}))

(defmacro ns
  "Sets *ns* to the namespace named by name (unevaluated), creating it
  if needed.  references can be zero or more of: (:refer-clojure ...)
  (:require ...) (:use ...) (:import ...) (:load ...) (:gen-class)
  with the syntax of refer-clojure/require/use/import/load/gen-class
  respectively, except the arguments are unevaluated and need not be
  quoted. (:gen-class ...), when supplied, defaults to :name
  corresponding to the ns name, :main true, :impl-ns same as ns, and
  :init-impl-ns true. All options of gen-class are
  supported. The :gen-class directive is ignored when not
  compiling. If :gen-class is not supplied, when compiled only an
  nsname__init.class will be generated. If :refer-clojure is not used, a
  default (refer 'clojure.core) is used.  Use of ns is preferred to
  individual calls to in-ns/require/use/import:

  (ns foo.bar
    (:refer-clojure :exclude [ancestors printf])
    (:require (clojure.contrib sql combinatorics))
    (:use (my.lib this that))
    (:import (java.util Date Timer Random)
             (java.sql Connection Statement)))"
  {:arglists '([name docstring? attr-map? references*])
   :added "1.0"}
  [name & references]
  (let [process-reference
        (fn [[kname & args]]
          `(~(symbol "clojure.core" (clojure.core/name kname))
             ~@(map #(list 'quote %) args)))
        docstring (when (string? (first references)) (first references))
        references (if docstring (next references) references)
        name (if docstring
               (vary-meta name assoc :doc docstring)
               name)
        metadata (when (map? (first references)) (first references))
        references (if metadata (next references) references)
        name (if metadata
               (vary-meta name merge metadata)
               name)
        gen-class-clause (first (filter #(= :gen-class (first %)) references))
        gen-class-call
        (when gen-class-clause
          (list* `gen-class :name (.replace (str name) \- \_) :impl-ns name :main true (next gen-class-clause)))
        references (remove #(= :gen-class (first %)) references)
        ;ns-effect (clojure.core/in-ns name)
        name-metadata (meta name)]
    `(do
       (clojure.core/in-ns '~name)
       ~@(when name-metadata
           `((.resetMeta (clojure.lang.Namespace/find '~name) ~name-metadata)))
       (with-loading-context
         ~@(when gen-class-call (list gen-class-call))
         ~@(when (and (not= name 'clojure.core) (not-any? #(= :refer-clojure (first %)) references))
             `((clojure.core/refer '~'clojure.core)))
         ~@(map process-reference references))

       (try
         (swap! shadow-load-ref assoc
           (str \/ (.. ~(str name) (replace \- \_) (replace \. \/)))
           (System/currentTimeMillis))
         (catch Exception e#
           (prn [:meh ~(str name) (.getMessage e#)])))

       (if (.equals '~name 'clojure.core)
         nil
         (do (dosync (commute @#'*loaded-libs* conj '~name)) nil))
       )))

(defn load
  "Loads Clojure code from resources in classpath. A path is interpreted as
  classpath-relative if it begins with a slash or relative to the root
  directory for the current namespace otherwise."
  {:redef true
   :added "1.0"}
  [& paths]
  (doseq [^String path paths]
    (let [start (System/currentTimeMillis)
          result
          (let [^String path (if (.startsWith path "/")
                               path
                               (str (root-directory (ns-name *ns*)) \/ path))]
            (check-cyclic-dependency path)
            (when-not (= path (first *pending-paths*))
              (binding [*pending-paths* (conj *pending-paths* path)]
                (clojure.lang.RT/load (.substring path 1)))))]

      (when (get @shadow-load-ref path)
        (prn [:load path (- (System/currentTimeMillis) (get @shadow-load-ref path))]))
      result)))
