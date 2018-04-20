(ns shadow.build.log
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]))

(defprotocol BuildLog
  (log* [this build-state log-event]))

(defmulti event->str
  (fn [event] (:type event))
  :default ::default)

(defn timing-prefix [{:keys [depth timing duration] :as event} msg]
  (str (when (= :exit timing) "<-")
       (str/join (repeat depth "-") "")
       (when (= :enter timing) "->")
       " "
       msg
       (when (= :exit timing)
         (format " (%d ms)" duration))))

(defn event-text [evt]
  (let [s (event->str evt)]
    (if (contains? evt :timing)
      (timing-prefix evt s)
      s)))

(defonce stdout-lock (Object.))

(def stdout
  (reify BuildLog
    (util/log* [_ state evt]
      (locking stdout-lock
        (println (event-text evt))
        ))))

(defmethod event->str ::default
  [event]
  (pr-str event))

(defmethod event->str :compile-cljs
  [{:keys [resource-name] :as event}]
  (format "Compile CLJS: %s" resource-name))

(defmethod event->str :compile-sources
  [{:keys [source-ids n-compile-threads] :as event}]
  (format "Compiling %d CLJS sources" (count source-ids)))

(defmethod event->str :cache-read
  [{:keys [resource-name] :as event}]
  (format "Cache read: %s" resource-name))

(defmethod event->str :cache-write
  [{:keys [resource-name] :as event}]
  (format "Cache write: %s" resource-name))

(defmethod event->str :flush-unoptimized
  [{:keys [name] :as event}]
  "Flushing unoptimized modules")

(defmethod event->str :flush-optimized [event]
  "Flushing optimized modules")

(defmethod event->str :flush-module
  [{:keys [name output-name js-size] :as event}]
  (format "Flushing: %s (%d bytes)" output-name js-size))

(defmethod event->str :dead-module
  [{:keys [name] :as event}]
  (format "dead/moved: %s" name))

(defmethod event->str :flush-foreign
  [{:keys [name js-name js-size] :as event}]
  (format "Flushing: %s (%d bytes)" js-name js-size))

(defmethod event->str :flush-sources
  [{:keys [source-ids] :as event}]
  (format "Flushing %s sources" (count source-ids)))

(defmethod event->str :flush-source-maps
  [event]
  (format "Flushing source maps"))

(defmethod event->str :find-resources
  [{:keys [path] :as event}]
  (format "Finding resources in: %s" path))

(defmethod event->str :find-resources-classpath
  [{:keys [path] :as event}]
  (format "Finding resources in classpath" path))

(defmethod event->str :closure-optimize
  [event]
  (format "Closure - Optimizing ..."))

(defmethod event->str :closure-check
  [event]
  (format "Closure - Checking ..."))

(defmethod event->str :bad-resource
  [{:keys [url] :as event}]
  (format "Ignoring bad file, it attempted to provide cljs.core%n%s" url))

(defmethod event->str :invalid-resource
  [{:keys [url] :as data}]
  (format "Invalid Resource %s%n%s"
    url
    (with-out-str
      (s/explain-out data))))

(defmethod event->str :duplicate-resource
  [{:keys [name path-use path-ignore] :as event}]
  (format
    "duplicate file on classpath \"%s\" (using A)%nA: %s%nB: %s"
    name
    path-use
    path-ignore))

(defmethod event->str :provide-conflict
  [{:keys [source-path name provides conflict-with]}]
  (str (format "Provide conflict: %s -> %s\n" name provides)
       (format "File: %s/%s (will not be used)\n" source-path name)
       (->> conflict-with
            (map (fn [[file provides]]
                   (format "Conflict: %s -> %s" file provides)))
            (str/join "\n"))))

(defmethod event->str :reload
  [{:keys [action ns name file]}]
  (format "RELOAD: %s" name))

(defmethod event->str :warning
  [{:keys [name warning]}]
  (let [{:keys [msg line column]} warning]
    (str "WARNING: " msg " (" name " at " line ":" column ") ")))

(defmethod event->str :name-violation
  [{:keys [src expected url]}]
  (let [{:keys [name ns source-path]}
        src]
    (str "File violation: \"" name "\" produced unexpected ns \"" ns "\""
         "\n\tExpected: " source-path "/" expected
         "\n\tProvided: " source-path "/" name
         )))

(defmethod event->str :cache-error
  [{:keys [action ns error]}]
  (format "Failed %s cache for %s: %s" (case action :read "reading" :write "writing") ns (.getMessage error)))

(defmethod event->str :cache-skip
  [{:keys [ns]}]
  (format "Cache skipped due to warnings: %s" ns))

(defmethod event->str :cache-block
  [{:keys [ns block-reasons]}]
  (format "Cache blocked \"%s\" due to %s" ns block-reasons))

(defmethod event->str :npm-flush
  [{:keys [output-path]}]
  (format "NPM module flush: %s" output-path))

