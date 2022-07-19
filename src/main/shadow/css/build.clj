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

(defn conform [[_ & body :as form] ns]
  (let [{:keys [line column] :as m} (meta form)]
    (merge {:ns ns :css-id (s/generate-id ns line column)} m (s/conform body))))

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

      (generate-default-aliases)))

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