(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require
    [clojure.java.io :as io]))

(defn get-shadow-cljs-info []
  (try
    (let [[defproject name version & kv :as rc]
          (-> (io/resource "META-INF/leiningen/thheller/shadow-cljs/project.clj")
              (slurp)
              (read-string))]

      (when (= name 'thheller/shadow-cljs)
        (let [{:keys [dependencies] :as data}
              (apply hash-map kv)

              dep-map
              (reduce
                (fn [m [name version]]
                  (assoc m name version))
                {}
                dependencies)]

          {:version version
           :dependencies dep-map})))
    (catch Exception e
      nil)))

(def important-deps
  '[org.clojure/clojure
    org.clojure/clojurescript
    com.google.javascript/closure-compiler-unshaded])

(defn load-error [e]
  (println "--- SHADOW-CLJS FAILED TO LOAD! ----------------------")
  (println)
  (println "This is most commonly caused by a dependency conflict.")
  (println "When using deps.edn or project.clj you must ensure that all")
  (println "required dependencies are provided with the correct version.")
  (println)

  (when-let [{:keys [version dependencies]} (get-shadow-cljs-info)]
    (println "You are using shadow-cljs version:" version)
    (println)
    (println "The important dependencies are:")
    (println)
    (doseq [dep important-deps]
      (println (str "  " dep " \"" (get dependencies dep) "\"")))
    (println)
    (println "Please verify that you are loading these versions.")
    (println "You can find all required dependencies here:")
    (println)
    (println (str "  https://clojars.org/thheller/shadow-cljs/versions/" version))
    (println))

  (println "Please refer to the Guide for more information:")
  (println)
  (println "  https://shadow-cljs.github.io/docs/UsersGuide.html#failed-to-load")
  (println)
  (println "-----------------------------------------------------")

  (when e
    (println)
    (println "The error encountered was:")
    (println)
    (.printStackTrace e)))

(defn -main [& args]
  (when-let [actual-main
             (try
               (requiring-resolve 'shadow.cljs.devtools.cli-actual/-main)
               (catch Exception e
                 (load-error e)
                 (System/exit 1)))]

    (apply actual-main args)))

(defn from-remote [complete-token error-token args]
  (let [actual (requiring-resolve 'shadow.cljs.devtools.cli-actual/from-remote)]
    (actual complete-token error-token args)))