(ns shadow.npm.cli
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(def npm-opts
  [["-r" "--runtime TARGET" "node or browser (default: browser)"
    :parse-fn keyword]
   ["-e" "--entries NAMESPACES" "comma seperated list of CLJS entry namespaces"
    :parse-fn
    #(->> (str/split % #",") (map symbol) (into []))]
   ;; this needs work, assume for now that the CWD is the root
   #_["-d" "--dir ROOT" "Root directory that contains node_modules folder"
      :default "./"]
   ["-w" "--watch"]
   ["-v" "--verbose"]
   ["-h" "--help"]])

(defn npm [& args]
  (let [{:keys [options errors summary help] :as opts}
        (cli/parse-opts args npm-opts)

        loaded?
        (find-ns 'shadow.cljs.devtools.api)]

    (when-not loaded?
      (println "shadow.npm.cli - loading ... this takes a while ..."))

    (if (or (seq errors) (:help options))
      (do (doseq [err errors]
            (println err))
          (println "Command line args:")
          (println "-----")
          (println summary)
          (println "-----"))

      (do (when-not loaded?
            (require 'shadow.cljs.devtools.api)
            (println "shadow.npm.cli - finished loading"))

          (let [{:keys [entries watch]}
                options

                build-config
                (merge options {:id :npm-module :target :npm-module})]

            (if-not watch
              ((find-var 'shadow.cljs.devtools.api/once*) build-config)
              ((find-var 'shadow.cljs.devtools.api/dev-watch*) build-config)
              ))))))

(defn -main [& args]
  (apply npm args))