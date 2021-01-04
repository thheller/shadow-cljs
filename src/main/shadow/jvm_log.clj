(ns shadow.jvm-log
  "logging setup tailored for shadow-cljs needs, not a generic logging lib"
  (:require [clojure.repl :as repl])
  (:import [java.util.logging Level Logger ConsoleHandler Formatter]
           [java.time.format DateTimeFormatter]
           [java.time LocalDateTime Instant ZoneId]))

(def ts-format (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.SSS"))

(defn format-millis [milli]
  (.format ts-format
    (-> (Instant/ofEpochMilli milli)
        (LocalDateTime/ofInstant (ZoneId/systemDefault)))))

(defn get-level [level]
  (case level
    :debug Level/FINE
    :info Level/INFO
    :warn Level/WARNING
    (throw (ex-info "invalid log level" {:level level}))))

(def base-formatter
  ;; since simpleformatter can only be configured via system properties
  ;; we roll our own so everything has a standard log format
  ;; we don't need any customization as that just makes bug reports harder to read
  (proxy [Formatter] []
    (format [log-record]
      (str (format "[%s - %s] %s%n"
             (format-millis (.getMillis log-record))
             (.getLevel log-record)
             (.getMessage log-record))
           (when-let [ex (.getThrown log-record)]
             (with-out-str
               (binding [*err* *out*]
                 (repl/pst ex))))))))

(def base-out
  (doto (ConsoleHandler.)
    (.setLevel (get-level :info))
    (.setFormatter base-formatter)))

(def ^Logger logger
  ;; setup a basic logger that does not inherit any default config
  ;; I do not want logging to behave differently depending
  ;; on which logging library is on the classpath or how its configured
  (let [log (Logger/getLogger "SHADOW_LOGGER")]
    (.setUseParentHandlers log false)
    (.setLevel log (get-level :debug))

    (doseq [handler (.getHandlers log)]
      (.removeHandler log handler))

    (.addHandler log base-out)

    log))

(defn set-level! [level]
  (.setLevel base-out (get-level level)))

(defmulti log-msg (fn [id data] id) :default ::default)

(defmethod log-msg ::default [id data]
  (if (seq data)
    (str id " - " (pr-str data))
    (str id)))

(defn do-log-ex [^Logger logger ^Level level ^String msg ^Throwable ex]
  (.log logger level msg ex))

(defn do-log [^Logger logger ^Level level ^String msg]
  (.log logger level msg))

(defn make-log-call [level log-id log-data log-ex]
  {:pre [(qualified-keyword? log-id)]}
  (let [level-sym (gensym "log-level")]
    `(let [~level-sym (get-level ~level)]
       (when (.isLoggable logger ~level-sym)
         ~(if log-ex
            `(do-log-ex logger ~level-sym (log-msg ~log-id ~log-data) ~log-ex)
            `(do-log logger ~level-sym (log-msg ~log-id ~log-data)))))))

(defmacro debug
  ([log-id]
   (make-log-call :debug log-id nil nil))
  ([log-id log-data]
   (make-log-call :debug log-id log-data nil)))

(defmacro debug-ex
  ([log-ex log-id]
   (make-log-call :debug log-id nil log-ex))
  ([log-ex log-id log-data]
   (make-log-call :debug log-id log-data log-ex)))

(defmacro info
  ([log-id]
   (make-log-call :info log-id nil nil))
  ([log-id log-data]
   (make-log-call :info log-id log-data nil)))

(defmacro info-ex
  ([log-ex log-id]
   (make-log-call :info log-id nil log-ex))
  ([log-ex log-id log-data]
   (make-log-call :info log-id log-data log-ex)))

(defmacro warn
  ([log-id]
   (make-log-call :warn log-id nil nil))
  ([log-id log-data]
   (make-log-call :warn log-id log-data nil)))

(defmacro warn-ex
  ([log-ex log-id]
   (make-log-call :warn log-id nil log-ex))
  ([log-ex log-id log-data]
   (make-log-call :warn log-id log-data log-ex)))