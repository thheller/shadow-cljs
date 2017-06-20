(ns shadow.cljs.devtools.cli-opts
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]))

(def cli-spec
  ;; FIXME: how do I make this not show up in summary?
  [[nil "--npm" "internal, used by the shadow-cljs npm package"]

   ;; generic
   [nil "--debug"]
   ["-v" "--verbose" "verbose build log"]
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

   :test

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
    (assoc result :builds (into [] (map keyword) arguments))))

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
    (if (:errors parsed)
      parsed
      (parse-arguments parsed)
      )))