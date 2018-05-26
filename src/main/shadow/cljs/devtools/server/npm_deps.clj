(ns shadow.cljs.devtools.server.npm-deps
  "utility namespaces for installing npm deps found in deps.cljs files"
  (:require [clojure.edn :as edn]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [shadow.cljs.devtools.server.util :as util])
  (:import (javax.script ScriptEngineManager ScriptEngine Invocable)))

(defn make-engine []
  (let [engine
        (-> (ScriptEngineManager.)
            (.getEngineByName "nashorn"))

        semver-js
        (slurp (io/resource "shadow/build/js/semver.js"))]

    (.eval engine semver-js)

    engine))

(def engine-lock (Object.))

(def semver-intersects
  (let [engine (delay (make-engine))]
    (fn [a b]
      ;; not sure if the engine is safe to use from multiple threads
      ;; better be sure
      (locking engine-lock
        (try
          (.invokeFunction @engine "shadowIntersects" (into-array Object [a b]))
          (catch Exception e
            (prn [:failed-to-compare a b e])
            true))))))

(comment
  (semver-intersects "^1.0.0" "^1.1.0")
  (semver-intersects "github:foo" "github:foo")
  (semver-intersects ">=1.3.0" "1.2.0")
  (semver-intersects "^2.0.0" "^1.1.0"))

(defn dep->str [dep-id]
  (cond
    (keyword? dep-id)
    ;; :some/foo? :react :@some/scoped?
    (subs (str dep-id) 1)

    (symbol? dep-id)
    (str dep-id)

    (string? dep-id)
    dep-id

    :else
    (throw (ex-info (format "invalid dependency id %s" dep-id) {}))))

(defn resolve-conflict
  [deps-to-install
   {a-version :version a-url :url :as a id :id}
   {b-version :version b-url :url :as b}]
  (cond
    (semver-intersects a-version b-version)
    deps-to-install

    (semver-intersects b-version a-version)
    (assoc deps-to-install id b)

    :else
    (do (println (format "NPM version conflict for \"%s\" in deps.cljs (will use A)" id))
        (println (format "A: \"%s\" from %s" a-version a-url))
        (println (format "B: \"%s\" from %s" b-version b-url))
        deps-to-install)))

(defn resolve-conflicts [deps]
  (let [deps-to-install
        (reduce
          (fn [deps-to-install {:keys [id version] :as dep}]
            (if-let [conflict (get deps-to-install id)]
              (resolve-conflict deps-to-install conflict dep)
              (assoc deps-to-install id dep)))
          {}
          deps)]

    (vals deps-to-install)))

(defn guess-node-package-manager [config]
  (or (get-in config [:node-modules :managed-by])
      (let [yarn-lock (io/file "yarn.lock")]
        (when (.exists yarn-lock)
          :yarn))
      :npm))

(defn install-deps [config deps]
  (let [args
        (for [{:keys [id version]} deps]
          (str id "@" version))

        install-cmd
        (case (guess-node-package-manager config)
          :yarn
          ["yarn" "add"]
          :npm
          ["npm" "install" "--save"])

        full-cmd
        (into install-cmd args)

        _ (println (str "running: " (str/join " " full-cmd)))

        proc
        (-> (ProcessBuilder. (into-array full-cmd))
            (.directory nil)
            (.start))]

    (-> (.getOutputStream proc)
        (.close))

    (.start (Thread. (bound-fn [] (util/pipe proc (.getInputStream proc) *out*))))
    (.start (Thread. (bound-fn [] (util/pipe proc (.getErrorStream proc) *err*))))

    (.waitFor proc)))

(defn get-deps-from-classpath []
  (let [deps
        (-> (Thread/currentThread)
            (.getContextClassLoader)
            (.getResources "deps.cljs")
            (enumeration-seq)
            (->> (map (fn [url]
                        (-> (slurp url)
                            (edn/read-string)
                            (select-keys [:npm-deps])
                            (assoc :url url))))
                 (into [])))]

    (vec (for [{:keys [url npm-deps]} deps
               [dep-id dep-version] npm-deps]
           {:id (dep->str dep-id)
            :version dep-version
            :url url}))
    ))

(defn read-package-json []
  (let [package-json-file (io/file "package.json")]
    (if-not (.exists package-json-file)
      {}
      (-> (slurp package-json-file)
          (json/read-str)))))

(defn is-installed? [{:keys [id version url]} package-json]
  (let [installed-version
        (or (get-in package-json ["dependencies" id])
            (get-in package-json ["devDependencies" id]))]
    (if-not (seq installed-version)
      false
      (do (when-not (semver-intersects installed-version version)
            (println
              (format "NPM dependency \"%s\" has installed version \"%s\"%n\"%s\" was required by %s"
                id
                installed-version
                version
                url)))
          true))))

(defn main [config opts]
  (let [package-json
        (read-package-json)

        deps
        (->> (get-deps-from-classpath)
             (resolve-conflicts)
             (remove #(is-installed? % package-json)))]

    (when (seq deps)
      (install-deps config deps)
      )))
