(ns shadow.css.build
  (:require
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as reader-types]
    [clojure.string :as str]
    [shadow.css.specs :as s]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [shadow.build.classpath :as cp]
    [shadow.build.data :as data]
    [shadow.build.cache :as cache]
    [shadow.jvm-log :as log]
    [shadow.build.resource :as rc])
  (:import [java.io File]
           [java.util.jar JarFile JarEntry]
           [java.util.zip ZipException]
           [java.net URL]))

(set! *warn-on-reflection* true)

(def CACHE-TIMESTAMP (System/currentTimeMillis))

;; FIXME: when parsing respect ns forms and support qualified uses
;; this currently only looks for (css ...) calls
;; it doesn't actually look for (shadow.grove/css ...) calls
;; but this is just easier than going full blown analyzer with support for aliases/scoping

(defn find-css-defs [defs form]
  (cond
    (and (list? form) (= 'css (first form)))
    ;; need to extract meta here, since caching will lose it on the form
    (conj! defs form)

    (map? form)
    (reduce find-css-defs defs (vals form))

    (coll? form)
    (reduce find-css-defs defs form)

    :else
    defs))

(defn find-css-in-source [src]
  ;; shortcut if src doesn't contain any css, faster than actually parsing
  (if-not (str/index-of src "(css")
    []
    (let [reader (reader-types/source-logging-push-back-reader src)
          eof (Object.)]

      (binding
        [reader/*alias-map*
         (fn [sym]
           'doesnt.matter)

         reader/*default-data-reader-fn*
         (fn [tag data]
           data)]

        (loop [css-defs (transient [])]
          (let [form (reader/read {:eof eof :read-cond :preserve} reader)]
            (if (identical? form eof)
              (persistent! css-defs)
              (recur (find-css-defs css-defs form)))))))))

(defn conform [[_ & body :as form] ns]
  (let [{:keys [line column] :as m} (meta form)]
    (merge {:ns ns :css-id (s/generate-id ns line column)} m (s/conform body))))

(defn clj-file? [filename]
  ;; .clj .cljs .cljc .cljd
  (str/index-of filename ".clj"))

(defn guess-ns [resource-name]
  (-> resource-name
      (subs 0 (str/last-index-of resource-name ".clj"))
      (str/replace #"_" "-")
      (str/replace #"/" ".")
      (symbol)))

(defn find-in-folder [svc ^File root]
  (let [root-path (.toPath root)]

    (->> (file-seq root)
         (filter #(clj-file? (.getName ^File %)))
         (map (fn [^File file]
                (let [file-path (.toPath file)

                      resource-name
                      (-> (.relativize root-path file-path)
                          (str)
                          ;; in case of windows
                          (str/replace #"\\" "/"))

                      guessed-ns
                      (guess-ns resource-name)]

                  {:resource-name resource-name
                   :ns guessed-ns
                   :last-modified (.lastModified file)
                   :file file}))))))

(defn find-in-jar*
  [svc ^File file checksum]
  (try
    (let [jar-path
          (.getCanonicalPath file)

          jar-file
          (JarFile. file)

          last-modified
          (.lastModified file)

          entries
          (.entries jar-file)]

      (loop [result (transient [])]
        (if (not (.hasMoreElements entries))
          (persistent! result)

          ;; next entry
          (let [^JarEntry jar-entry (.nextElement entries)
                name (.getName jar-entry)
                resource-name (rc/normalize-name name)]

            (if (or (str/starts-with? name "META-INF")
                    (.isDirectory jar-entry)
                    (not (clj-file? resource-name)))
              (recur result)
              (let [url (URL. (str "jar:file:" jar-path "!/" name))]
                (-> result
                    (conj! {:resource-name resource-name
                            :url url
                            :cache-key [checksum]
                            :last-modified last-modified
                            :ns (guess-ns resource-name)})
                    (recur)
                    )))))))

    (catch ZipException e
      (log/debug-ex e ::bad-jar {:file file})
      [])

    (catch Exception e
      (throw (ex-info (str "failed to generate jar manifest for file: " file) {:file file} e)))))

(defn find-in-jar
  [{:keys [manifest-cache-dir] :as svc} ^File jar-file]
  (let [checksum
        (data/sha1-file jar-file)

        manifest-name
        (str (.getName jar-file) "." checksum ".manifest")

        mfile
        (io/file manifest-cache-dir manifest-name)]

    (or (when (and (.exists mfile)
                   (>= (.lastModified mfile) (.lastModified jar-file))
                   (>= (.lastModified mfile) CACHE-TIMESTAMP))
          (try
            (let [cache (cache/read-cache mfile)]
              ;; user downloads version 2.0.0 runs it
              ;; upgrades to latest version release a day ago
              ;; last-modified of cache is higher that release data
              ;; so the initial check succeeds because >= is true
              ;; comparing them to be equal ensures that new version
              ;; will invalidate the cache
              (when (= CACHE-TIMESTAMP (::CACHE-TIMESTAMP cache))
                (:resources cache)))
            (catch Throwable e
              (log/info-ex e ::jar-cache-read-ex {:file mfile})
              nil)))

        (let [jar-contents (find-in-jar* svc jar-file checksum)]
          (io/make-parents mfile)
          (try
            (cache/write-file mfile {::CACHE-TIMESTAMP CACHE-TIMESTAMP
                                     :resources jar-contents})
            (catch Throwable e
              (log/info-ex e ::jar-cache-write-ex {:file mfile})))
          jar-contents))))

(defn find-resources-in-path [svc ^File root]
  (cond
    (not (.exists root))
    []

    ;; lots of files, none with (css
    (or (str/index-of (.getAbsolutePath root) "cljsjs"))
    []

    (.isDirectory root)
    (find-in-folder svc root)

    (and (.isFile root)
         (str/ends-with? (str/lower-case (.getName root)) ".jar"))
    (find-in-jar svc root)

    ;; FIXME: silently ignoring invalid classpath entry?
    :else
    []))

;; same naming patterns tailwind uses
(def spacing-alias-groups
  {"px-" [:padding-left :padding-right]
   "py-" [:padding-top :padding-bottom]
   "p-" [:padding]
   "mx-" [:margin-left :margin-right]
   "my-" [:margin-top :margin-bottom]
   "w-" [:width]
   "max-w-" [:max-width]
   "h-" [:height]
   "max-h-" [:max-height]
   "basis-" [:flex-basis]
   "gap-" [:gap]
   "gap-x-" [:column-gap]
   "gap-y-" [:row-gap]})

(defn generate-default-aliases [{:keys [spacing] :as svc}]
  (update svc :aliases
    (fn [aliases]
      (reduce-kv
        (fn [aliases space-num space-val]
          (reduce-kv
            (fn [aliases prefix props]
              (assoc aliases
                (keyword (str prefix space-num))
                (reduce #(assoc %1 %2 space-val) {} props)))
            aliases
            spacing-alias-groups))
        aliases
        spacing))))

(defn load-default-aliases []
  (edn/read-string (slurp (io/resource "shadow/css/aliases.edn"))))

(defn index-classpath [{:keys [index-ref] :as svc}]
  (let [all
        (->> (cp/get-classpath)
             (mapcat #(find-resources-in-path svc %))
             (reduce
               (fn [idx {:keys [ns] :as rc}]
                 (assoc idx ns rc))
               {}))]

    (swap! index-ref merge all)
    svc))

(defn start [config]
  (-> {:config
       config

       :index-ref
       (atom {})

       :manifest-cache-dir
       (doto (io/file ".shadow-cljs" "css")
         (io/make-parents))

       :aliases
       (load-default-aliases)

       ;; https://tailwindcss.com/docs/customizing-spacing#default-spacing-scale
       :spacing
       {0 "0"
        0.5 "0.125rem"
        1 "0.25rem"
        1.5 "0.375rem"
        2 "0.5rem"
        2.5 "0.626rem"
        3 "0.75rem"
        3.5 "0.875rem"
        4 "1rem"
        5 "1.25rem"
        6 "1.5rem"
        7 "1.75rem"
        8 "2rem"
        9 "2.25rem"
        10 "2.5rem"
        11 "2.75rem"
        12 "3rem"
        13 "3.25rem"
        14 "3.5rem"
        15 "3.75rem"
        16 "4rem"
        17 "4.25rem"
        18 "4.5rem"
        19 "4.75rem"
        20 "5rem"
        24 "6rem"
        28 "7rem"
        32 "8rem"
        36 "9rem"
        40 "10rem"
        44 "11rem"
        48 "12rem"
        52 "13rem"
        56 "14rem"
        60 "15rem"
        64 "16rem"
        96 "24rem"}

       :normalize-src
       (slurp (io/resource "shadow/css/modern-normalize.css"))}

      (generate-default-aliases)
      (index-classpath)))

(defn stop [svc])


(comment
  (time
    (tap> (start {})))

  (require 'clojure.pprint)
  (println
    (generate-css
      (start)
      [(-> (s/conform!
             '[:px-4 {:padding 2} :flex {:foo "yo" :bar ("url(" :ui/foo ")")}
               ["@media (prefers-color-scheme: dark)"
                [:ui/md :px-8
                 ["&:hover" {:color "green"}]]]])
           (assoc :css-id "foo" :ns "foo.bar" :line 3 :column 1))]))

  (time
    (tap>
      (->> (clojure.java.io/resource "dummy/app.cljs")
           (slurp)
           (find-css-in-source)
           (map #(conform % "dummy.app"))
           (vec)))))