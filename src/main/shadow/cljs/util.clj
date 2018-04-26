(ns shadow.cljs.util
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]
            [cljs.compiler :as comp]
            [cljs.core]
            [shadow.build.log :as log]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import (clojure.lang Namespace IDeref)
           (java.io File StringWriter ByteArrayOutputStream)
           (java.security MessageDigest)
           (java.nio.charset Charset)
           [java.net URLConnection URL]))

(defn build-state? [state]
  ;; not using shadow.build.data because of a cyclic dependency I need to clean up
  (and (map? state)
       (true? (:shadow.build.data/build-state state))))

(defn foreign? [{:keys [type] :as src}]
  (= :foreign type))

(defn file-basename [^String path]
  (let [idx (.lastIndexOf path "/")]
    (.substring path (inc idx))
    ))

(defn munge-goog-ns [s]
  (-> s
      (str/replace #"_" "-")
      (symbol)))

(defn file? [file]
  (and file (instance? File file)))

(defn flat-filename [filename]
  (str/replace filename #"/" "."))

(defn flat-js-name [name]
  (let [ext (str/last-index-of name ".")]
    (flat-filename (str (subs name 0 ext) ".js"))))

(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

(defn is-relative? [entry]
  (str/starts-with? entry "."))

(defn is-absolute? [entry]
  (str/starts-with? entry "/"))

(defn is-package-require? [require]
  (and (not (is-relative? require))
       (not (is-absolute? require))))

(defn is-jar? [^String name]
  (.endsWith (str/lower-case name) ".jar"))

(defn is-cljs-file? [^String name]
  (or (.endsWith (str/lower-case name) ".cljs")
      (.endsWith (str/lower-case name) ".cljc")))

(defn is-cljc? [^String name]
  (.endsWith name ".cljc"))

(defn is-cljs? [^String name]
  (.endsWith name ".cljs"))

(defn is-js-file? [^String name]
  (.endsWith (str/lower-case name) ".js"))

(defn is-file-instance? [x]
  (and x (instance? File x)))

(defn is-absolute-file? [x]
  (and (is-file-instance? x)
       ;; nrepl/load-file may want to load files that are not on disk yet
       ;; since its provide the source that is ok, should still be an absolute path though
       (or (not (.exists x)) (.isFile x))
       (.isAbsolute x)))

(defn is-directory? [x]
  (and (is-file-instance? x)
       (.isDirectory ^File x)))

(defn is-cljs-resource? [^String name]
  (or (is-cljs-file? name)
      (is-js-file? name)
      ))

(defn cljs->js-name [name]
  (str/replace name #"\.cljs$" ".js"))

(defn clj-name->ns
  "guesses ns from filename"
  [name]
  (-> name
      (str/replace #"\.clj(c)?$" "")
      (str/replace #"_" "-")
      (str/replace #"[/\\]" ".")
      (symbol)))

(defn ns->path [ns]
  (-> ns
      (str)
      (str/replace #"\." "/")
      (str/replace #"-" "_")))

(defn ns->cljs-filename [ns]
  (-> ns
      (ns->path)
      (str ".cljs")))

(defn filename->ns [^String name]
  {:pre [(or (.endsWith name ".js")
             (.endsWith name ".clj")
             (.endsWith name ".cljs")
             (.endsWith name ".cljc"))]}
  (-> name
      (str/replace #"\.(js|clj(s|c))$" "")
      (str/replace #"_" "-")
      (str/replace #"[/\\]" ".")
      (symbol)))

(defn conj-in [m k v]
  (update-in m k (fn [old] (conj old v))))

(defn set-conj [x y]
  (if x
    (conj x y)
    #{y}))

(defn vec-conj [x y]
  (if x
    (conj x y)
    [y]))

(defn has-tests? [{:keys [requires] :as rc}]
  (or (contains? requires 'cljs.test)
      (contains? requires 'clojure.test)))

(defn md5hex [^String text]
  (let [bytes
        (.getBytes text)

        md
        (doto (MessageDigest/getInstance "MD5")
          (.update bytes))

        sig
        (.digest md)]

    (reduce
      (fn [s b]
        (str s (format "%02X" b)))
      ""
      sig)))

(defn log-collector []
  (let [entries (atom [])]
    (reify
      log/BuildLog
      (log* [this build-state log-event]
        (swap! entries conj log-event))

      IDeref
      (deref [_]
        @entries))))

(defn log [state {::log/keys [level] :as log-event}]
  {:pre [(build-state? state)]}
  (log/log* (:logger state) state
    (-> log-event
        (cond->
          (not level)
          (assoc ::log/level :info))))
  state)

(defn error [state log-event]
  (log state (assoc log-event ::log/level :error)))

(defn warn [state log-event]
  (log state (assoc log-event ::log/level :warn)))

(def ^{:dynamic true} *time-depth* 0)

(defmacro with-logged-time
  [[state msg] & body]
  `(let [msg# ~msg
         start# (System/currentTimeMillis)

         evt#
         (assoc msg#
           :timing :enter
           :start start#
           :depth *time-depth*)]
     (log ~state evt#)
     (let [result#
           (binding [*time-depth* (inc *time-depth*)]
             ~@body)

           stop#
           (System/currentTimeMillis)

           evt#
           (assoc msg#
             :timing :exit
             :depth *time-depth*
             :stop stop#
             :duration (- stop# start#))]
       (log (if (build-state? result#) result# ~state) evt#)
       result#)
     ))

(defn tbd []
  (throw (ex-info "FIXME: TBD" {})))

;; these are from clojure.java.shell, since they are private ...
(defn stream-to-bytes [in]
  (with-open [bout (ByteArrayOutputStream.)]
    (io/copy in bout)
    (.toByteArray bout)))

(defn stream-to-string
  ([in] (stream-to-string in (.name (Charset/defaultCharset))))
  ([in enc]
   (with-open [bout (StringWriter.)]
     (io/copy in bout :encoding enc)
     (.toString bout))))

(defn stream-to-enc
  [stream enc]
  (if (= enc :bytes)
    (stream-to-bytes stream)
    (stream-to-string stream enc)))

(defn add-env [pb env]
  (.. pb (environment) (putAll env))
  pb)

(defn exec
  "modern clojure.java.shell/sh without the varargs crap
   using ProcessBuilder instead of Runtime.exec"
  [cmd {:keys [dir env in] :as opts}]
  {:pre [(sequential? cmd)
         (every? string? cmd)]}
  (let [pb
        (-> (ProcessBuilder. cmd)
            (cond->
              dir
              (.directory (io/as-file dir))
              env
              (add-env env)))

        proc
        (.start pb)

        {:keys [in in-enc out-enc]}
        opts]

    (if in
      (future
        (with-open [os (.getOutputStream proc)]
          (io/copy in os :encoding in-enc)))
      (.close (.getOutputStream proc)))
    (with-open [stdout (.getInputStream proc)
                stderr (.getErrorStream proc)]
      (let [out (future (stream-to-enc stdout out-enc))
            err (future (stream-to-string stderr))
            exit-code (.waitFor proc)]
        {:exit exit-code :out @out :err @err}))))

(defn url-last-modified [^URL url]
  (let [^URLConnection con (.openConnection url)
        ;; not looking at it but only way to close file:... connections
        ;; which keep the file open and will leak otherwise
        stream (.getInputStream con)]
    (try
      (.getLastModified con)
      (finally
        (.close stream)))))

(defn resource-last-modified [path]
  {:pre [(string? path)]}
  (url-last-modified (io/resource path)))
