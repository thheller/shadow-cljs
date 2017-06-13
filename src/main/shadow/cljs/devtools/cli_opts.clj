(ns shadow.cljs.devtools.cli-opts
  (:require [clojure.tools.cli :as cli]))

(defn mode-cli-opt [opt description]
  [nil opt description
   :assoc-fn
   (fn [m k v]
     (when-let [mode (get m ::mode)]
       (println (format "overwriting mode %s -> %s, please only use one mode" mode k)))
     (assoc m ::mode k))])

(def cli-spec
  [(mode-cli-opt "--dev" "mode: dev (will watch files and recompile, REPL, ...)")
   (mode-cli-opt "--once" "mode: compile once and exit")
   (mode-cli-opt "--release" "mode: compile release version and exit")
   (mode-cli-opt "--check" "mode: closure compiler type check and exit")
   (mode-cli-opt "--server" "[WIP] server mode, doesn't do much yet")

   ;; exlusive
   ["-b" "--build BUILD-ID" "use build defined in shadow-cljs.edn"
    :id ::build
    :parse-fn keyword]
   [nil "--npm" "internal, used by the shadow-cljs npm package"
    :id ::npm]

   ;; generic
   [nil "--debug" "enable debug options, useful in combo with --release (pseudo-names, source-map)"]
   ["-v" "--verbose"]
   ["-h" "--help"
    :id ::help]])

(defn help [{:keys [errors summary] :as opts}]
  (do (doseq [err errors]
        (println err))
      (println "Command line args:")
      (println "-----")
      (println summary)
      (println "-----")))

(defn parse [args]
  (cli/parse-opts args cli-spec))