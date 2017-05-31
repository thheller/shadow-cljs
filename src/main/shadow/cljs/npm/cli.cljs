(ns shadow.cljs.npm.cli
  (:require ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]
            [shadow.cljs.npm.nrepl :as nrepl]))

(defn file-older-than [a b]
  (let [xa (fs/statSync a)
        xb (fs/statSync b)]
    (> (.-mtime xa) (.-mtime xb))))

(defn ensure-dir [dir]
  (when-not (fs/existsSync dir)
    (fs/mkdirSync dir)))

(defn lein-classpath-gen
  "returns true if generation was successful"
  [write-to]
  (let [result (cp/spawnSync "lein" #js ["classpath" write-to] #js {:stdio "inherit"})]
    (if (.-error result)
      (do (js/console.log "shadow-cljs - lein failed", (.-error result))
          false)
      (do (js/console.log "shadow-cljs - lein classpath generated successfully")
          true))))

(defn java-args-lein []
  (when (fs/existsSync "project.clj")
    (let [cache-path (path/resolve "target" "shadow-cljs" "lein-classpath.txt")

          lein-available?
          (or (and (fs/existsSync cache-path)
                   (not (file-older-than "project.clj" cache-path)))

              (ensure-dir (path/resolve "target"))
              (ensure-dir (path/resolve "target" "shadow-cljs"))
              (lein-classpath-gen cache-path))]

      (when lein-available?
        (let [cp (-> (fs/readFileSync cache-path)
                     (.toString))]
          (js/console.log "shadow-cljs - using lein classpath")

          ["-cp" cp "clojure.main" "-m" "shadow.cljs.devtools.cli" "--npm"]
          )))))

(defn java-args-standalone []
  (when (fs/existsSync "package.json")
    (let [version
          (js/require "./version.json")]

      (js/console.log "shadow-cljs - using package.json" version)

      ["-jar"
       (js/require "shadow-cljs-jar/path") ;; just exports the launcher jar path
       version
       "--npm"])))

(defn run [java-cmd java-args]
  (cp/spawnSync java-cmd (into-array java-args) #js {:stdio "inherit"}))

(defn try-nrepl [args]
  (when (fs/existsSync ".nrepl-port")

    (let [port
          (-> (fs/readFileSync ".nrepl-port")
              (.toString)
              (js/parseInt 10))]

      (nrepl/client port args)
      )))

(defn try-java [args]
  (when-let [java-args
             (or (java-args-lein)
                 (java-args-standalone))]

    (let [all-args
          (into java-args args)

          result
          (run "java" all-args)]

      (if (zero? (.-status result))
        true
        (when (and (.-error result)
                   (= "ENOENT" (.. result -error -errno)))

          (js/console.log "shadow-cljs - java not found, trying node-jre")

          (let [jre (js/require "node-jre")

                result
                (run (.driver jre) all-args)]
            ))))))

(defn meh []
  (js/console.log "shadow-cljs failed to start, missing package.json or project.clj?"))

(defn main [& args]
  (or (try-nrepl args)
      (try-java args)
      (meh)))
