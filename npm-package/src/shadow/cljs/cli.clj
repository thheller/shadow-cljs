(ns shadow.cljs.cli
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :as aether]
            ))

(defn transfer-listener
  [{:keys [type error resource] :as info}]
  (let [{:keys [name repository]} resource]
    (binding [*out* *err*]
      (case type
        :started (println "Retrieving" name "from" repository)
        ;; :progressed
        ;; :succeeded
        :corrupted (when error (println (.getMessage error)))
        nil))))

(def devtools-version "1.0.20170518")

(def default-config
  {"dependencies"
   []
   "source-paths"
   ["src-cljs"]})

(defn -main [& args]
  ;; this is purely configured through package.json, shouldn't look at args
  (let [package-json
        (io/file "package.json")

        {:strs [repositories dependencies version source-paths]
         :or {version devtools-version}
         :as config}
        (or (when (.exists package-json)
              (-> (slurp package-json)
                  (json/read-str)
                  (get "shadow-cljs")))
            default-config)

        coords
        (->> dependencies
             (map (fn [[name version & more]]
                    (when more
                      (throw (ex-info "FIXME: support more" {:more more})))
                    [(symbol name) version]))
             (into [['thheller/shadow-cljs version]]))]

    (println "shadow-cljs - loading dependencies")

    (pom/add-dependencies
      :coordinates
      coords
      :repositories
      (merge aether/maven-central {"clojars" "http://clojars.org/repo"} repositories)
      :transfer-listener
      transfer-listener)

    (doseq [path source-paths]
      (pom/add-classpath (io/file path)))

    (println "shadow-cljs - starting")

    (require 'shadow.cljs.devtools.cli)

    (apply (find-var 'shadow.cljs.devtools.cli/main) args))
  )