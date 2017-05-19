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

(def default-config
  {"dependencies"
   []
   "source-paths"
   ["src-cljs"]})


;; the shadow-cljs clojars version must be the first argument to this
;; this allows the shadow-cljs npm packages to be updated frequently without
;; requiring a new version of this launcher, since this is an addition 8MB
;; that will only require changes very infrequently

(defn -main [version & args]
  ;; this is purely configured through package.json, shouldn't look at args
  (let [package-json
        (io/file "package.json")

        {:strs [repositories dependencies source-paths]
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

    ;; FIXME: resolve conflicts?
    ;; the uberjar contains the dependencies listed in project.clj
    ;; those should be excluded from everything
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