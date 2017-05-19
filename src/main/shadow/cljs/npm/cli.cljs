(ns shadow.cljs.npm.cli
  (:require ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]))

(defn file-older-than [a b]
  (let [xa (fs/statSync a)
        xb (fs/statSync b)]
    (> (.-mtime xa) (.-mtime xb))))

(defn lein-classpath-gen
  "returns true if generation was successful"
  [write-to]
  (let [result (cp/spawnSync "lein" #js ["classpath" write-to] #js {:stdio "inherit"})]
    (if (.-error result)
      (do (js/console.log "lein failed", (.-error result))
          false)
      (do (js/console.log "lein classpath generated successfully")
          true))))

(defn java-args-lein []
  (when (fs/existsSync "project.clj")
    (let [cache-path (path/resolve ".shadow-cljs" "lein-classpath.txt")

          lein-available?
          (or (and (fs/existsSync cache-path)
                   (not (file-older-than "project.clj" cache-path)))
              (lein-classpath-gen cache-path))]

      (when lein-available?
        (let [cp (-> (fs/readFileSync cache-path)
                     (.toString))]
          (js/console.log "shadow-cljs - using lein classpath")

          ["-cp" cp "clojure.main" "-m" "shadow.cljs.devtools.cli" "--npm"]
          )))))

(defn java-args-standalone []
  (let [version
        (js/require "./version.json")]

    (js/console.log "shadow-cljs - using package.json" version)

    ["-jar"
     (js/require "shadow-cljs-jar/path") ;; just exports the launcher jar path
     version
     "--npm"]))

(defn run [java-cmd java-args]
  (cp/spawnSync java-cmd (into-array java-args) #js {:stdio "inherit"}))

(defn main [& args]
  (when-not (fs/existsSync ".shadow-cljs")
    (fs/mkdirSync ".shadow-cljs"))

  (let [java-args
        (or (java-args-lein)
            (java-args-standalone))

        result
        (run "java" java-args)]

    (when (and (.-error result)
               (= "ENOENT" (.. result -error -errno)))

      (js/console.log "shadow-cljs - java not found, trying node-jre")

      (let [jre (js/require "node-jre")

            result
            (run (.driver jre) java-args)]
        ))))