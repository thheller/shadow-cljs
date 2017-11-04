(ns shadow.cljs.devtools.cli-opts
  #?(:clj
     (:require
       [clojure.tools.cli :as cli]
       [clojure.string :as str])
     :cljs
     (:require
       [goog.string.format]
       [goog.string :refer (format)]
       [cljs.tools.cli :as cli]
       [clojure.string :as str])))

(defn parse-dep [dep-str]
  (let [[sym ver] (str/split dep-str #":")]
    [(symbol sym) ver]
    ))

(defn conj-vec [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(def cli-spec
  ;; FIXME: how do I make this not show up in summary?
  [[nil "--npm" "internal, used by the shadow-cljs npm package"]

   ["-d" "--dependency DEP" "adds an additional dependency (eg. -d foo/bar:1.2.3 -d another/thing:4.0.0)"
    :parse-fn parse-dep
    :assoc-fn
    (fn [opts k v]
      (update opts :dependencies conj-vec v))]
   ;; generic
   [nil "--source-maps" "temporarily enable source-maps for release debugging"]
   [nil "--pseudo-names" "temporarily enable pseudo-names for release debugging. DO NOT SHIP THIS CODE!"]
   [nil "--debug" "enable source-maps + pseudo-names. DO NOT SHIP THIS CODE!"]
   [nil "--stdin" "clj-eval from stdin"]
   ["-v" "--verbose" "verbose build log"]
   [nil "--cli-info" "prints a bunch of information"]
   [nil "--via VIA" "internal option, used by node script" :parse-fn keyword]
   ["-h" "--help"]])

(def action-help
  ;; per action help for: shadow-cljs compile -h
  {:compile "TBD"})

(def action-list
  [:compile
   :watch
   :check
   :release

   :node-repl
   :cljs-repl
   :clj-repl
   :clj-eval

   :info
   :pom

   :npm-deps

   :test

   :init
   :help
   :server])

(defn help [{:keys [errors summary] :as opts}]
  (do (doseq [err errors]
        (println)
        (println err)
        (println))

      (println "Usage:")
      (println "  shadow-cljs <action> <zero or more build ids>")
      (println)

      (println "Supported actions are:")
      (println)
      (doseq [action action-list]
        ;; FIXME: add help
        (println (format "%12s - ..." (name action))))
      (println)
      (println "Options:")
      (println "-----")
      (println summary)
      (println "-----")))

(def action-set
  (into #{} action-list))

(def actions-that-require-build-arg
  #{:compile
    :watch
    :release
    :check
    :cljs-repl})

(defn parse-build-arg [{:keys [action arguments] :as result}]
  (if (empty? arguments)
    (assoc result :errors [(str "Action \"" (name action) "\" requires one or more build ids")])
    ;; FIXME: validate build-ids
    (assoc result :builds (into [] (map (comp keyword #(str/replace %1 ":" ""))) arguments))))

(defn parse-arguments [{:keys [arguments] :as result}]
  (if (empty? arguments)
    (assoc result :errors ["Please specify which action to run!"])
    (let [action-str
          (first arguments)

          action
          (keyword action-str)]
      (if-not (contains? action-set action)
        (assoc result :errors [(str "Invalid action \"" action-str "\"")])
        (-> result
            (assoc :action action)
            (update :arguments subvec 1)
            (cond->
              (contains? actions-that-require-build-arg action)
              (parse-build-arg)
              ))))))

(defn parse [args]
  (let [parsed
        (cli/parse-opts args cli-spec)]
    (if (or (:errors parsed)
            (get-in parsed [:options :help]))
      parsed
      (parse-arguments parsed)
      )))