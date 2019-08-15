(ns shadow.cljs.devtools.cli-opts
  #?(:clj
     (:require
       [clojure.tools.cli :as cli]
       [shadow.cli-util :as cli-util]
       [shadow.cljs.devtools.config :as config]
       [clojure.string :as str])
     :cljs
     (:require
       [goog.string.format]
       [goog.string :refer (format)]
       [cljs.tools.cli :as cli]
       [shadow.cli-util :as cli-util]
       [clojure.string :as str])))

(defn parse-dep [dep-str]
  (let [[sym ver] (str/split dep-str #":")]
    [(symbol sym) ver]
    ))

#?(:cljs
   ;; don't actually need to parse this on the npm side
   ;; since it never compiles anything
   (defn parse-merge-data [edn-str]
     {})

   :clj
   (defn parse-merge-data [edn-str]
     (config/read-config-str edn-str)))

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

   [nil "--config-merge DATA" "merges additional EDN data into the build config"
    :parse-fn parse-merge-data
    :assoc-fn
    (fn [opts k v]
      (when-not (map? v)
        (throw (ex-info "--config-merge expects an EDN map argument" {:v v})))
      (update opts :config-merge conj-vec v))]
   ;; generic
   ["-A" "--aliases ALIASES" "adds aliases for use with clj, only effective when using deps.edn"]
   [nil "--source-maps" "temporarily enable source-maps for release debugging"]
   [nil "--pseudo-names" "temporarily enable pseudo-names for release debugging. DO NOT SHIP THIS CODE!"]
   [nil "--debug" "enable source-maps + pseudo-names. DO NOT SHIP THIS CODE!"]
   [nil "--stdin" "clj-eval from stdin"]
   ["-v" "--verbose" "verbose build log"]
   [nil "--force-spawn" "spawn new process, do not connect to running server"]
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
   :browser-repl

   :cljs-repl
   :clj-repl
   :clj-eval
   :clj-run
   :run

   :info
   :pom

   :npm-deps

   :test

   :aot
   :init
   :help
   :server

   :start
   :stop
   :restart
   ])

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

(defn split-at-run [args]
  (let [idx (or (.indexOf args "run")
                (.indexOf args "clj-run"))]
    (if (= -1 idx)
      [args []]
      [(subvec args 0 (inc idx))
       (subvec args (inc idx))]
      )))

(comment
  (prn (split-at-run ["a" "run" "b"])))

(defn parse [args]
  (let [[args extra] (split-at-run (vec args))
        parsed
        (-> (cli/parse-opts args cli-spec)
            (update :arguments into extra))]

    (if (or (:errors parsed) (get-in parsed [:options :help]))
      parsed
      (parse-arguments parsed)
      )))

(comment
  (prn (parse ["run" "--foo" "--bar"])))

(def cli-config
  {:aliases
   {"v" :verbose
    "h" :help}

   :init-command
   :global

   :commands
   {:global
    {:args-mode :none
     :aliases
     {"d" :dependency}
     :flags
     #{:force-spawn
       :npm
       :cli-info}
     :options
     {:dependency
      {:multiple true
       :parse-fn parse-dep}}}

    :help
    {:args-mode :none}

    :server
    {:args-mode :none}

    :node-repl
    {:args-mode :none
     :options
     {:node-arg
      {:multiple true}}}

    :browser-repl
    {:args-mode :none}

    :clj-repl
    {:args-mode :none}

    :run
    {:args-mode :eat-all}

    :clj-run
    {:alias-of :run}

    :compile
    {:args-mode :at-least-one}

    :watch
    {:args-mode :at-least-one}

    :release
    {:args-mode :at-least-one
     :flags
     #{:source-maps
       :pseudo-names
       :debug}}
    }})

(defn upgrade-args
  "rewrite old style args to new style"
  [old]
  (loop [[head & tail] old
         new []]
    (cond
      (nil? head)
      new

      (or (= head "-d")
          (= head "--dependency"))
      (let [dep (first tail)]
        (if (or (not dep)
                (str/starts-with? dep "-"))
          (throw (ex-info "invalid argument" {:arg head
                                              :dep dep
                                              :old old
                                              :new new}))
          (recur (rest tail) (conj new (str "--dependency=" dep)))
          ))

      :else
      (recur tail (conj new head))
      )))

(defn parse-main-cli [args]
  (cli-util/parse-args cli-config (upgrade-args args)))
