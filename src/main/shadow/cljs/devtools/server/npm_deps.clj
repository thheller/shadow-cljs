(ns shadow.cljs.devtools.server.npm-deps
  "utility namespaces for installing npm deps found in deps.cljs files"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.jvm-log :as log]))

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

  ;; FIXME: not actually resolving conflicts any longer because of semver.js removal
  ;; for now just ends up using the first declared version found on the classpath
  ;; should eventually do some kind of resolving but given how icky this whole thing
  ;; already is regardless I don't care for now.
  ;; should just remove the automatic install altogether and turn into standalone command
  ;; but that might break peoples project relying on automatic installs
  ;; this way they at least still get the proper dependency most of the time
  deps-to-install)

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
      (get-in config [:npm-deps :managed-by])
      (let [yarn-lock (io/file "yarn.lock")]
        (when (.exists yarn-lock)
          :yarn))

      :npm))

(defn fill-packages-placeholder [install-cmd packages]
  (let [full-cmd
        (reduce
          (fn [x y]
            (if (= y :packages)
              (into x packages)
              (conj x y)))
          []
          install-cmd)]

    ;; if no :packages was replaced just append the packages
    (if (not= full-cmd install-cmd)
      full-cmd
      (into install-cmd packages))))

(defn install-deps [config deps]
  (let [args
        (for [{:keys [id version]} deps]
          (str id "@" version))

        install-dir
        (get-in config [:npm-deps :install-dir] ".")

        install-package-json
        (io/file install-dir "package.json")

        install-cmd
        (or (get-in config [:node-modules :install-cmd])
            (get-in config [:npm-deps :install-cmd])
            (case (guess-node-package-manager config)
              :yarn
              ["yarn" "add" "--exact"]
              :npm
              ["npm" "install" "--save" "--save-exact"]))

        full-cmd
        (fill-packages-placeholder install-cmd args)

        ;; FIXME: replace this on windows to properly locate npm/yarn binaries instead
        full-cmd
        (if (str/includes? (System/getProperty "os.name") "Windows")
          (into ["cmd" "/C"] full-cmd)
          full-cmd)

        _ (when-not (.exists install-package-json)
            (io/make-parents install-package-json)
            (spit install-package-json "{}"))

        _ (println (str "running: " (str/join " " full-cmd)))

        proc
        (-> (ProcessBuilder. (into-array full-cmd))
            (.directory (io/file install-dir))
            (.start))]

    (-> (.getOutputStream proc)
        (.close))

    (.start (Thread. (bound-fn [] (util/pipe proc (.getInputStream proc) *out*))))
    (.start (Thread. (bound-fn [] (util/pipe proc (.getErrorStream proc) *err*))))

    (.waitFor proc)))

(comment
  (install-deps
    {:node-modules
     {:install-cmd ["hello" :packages "world"]}}
    [{:id "hello"
      :version "1.2.3"}]))

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

(defn read-package-json [install-dir]
  (let [package-json-file (io/file install-dir "package.json")]
    (if-not (.exists package-json-file)
      {}
      (-> (slurp package-json-file)
          (json/read-str)))))

(defn is-installed? [{:keys [id]} package-json]
  (or (get-in package-json ["dependencies" id])
      (get-in package-json ["devDependencies" id])
      (get-in package-json ["peerDependencies" id])))

(defn main [{:keys [npm-deps] :as config} opts]
  (when-not (false? (:install npm-deps))
    (let [{:keys [install-dir] :or {install-dir "."}}
          npm-deps

          package-json
          (read-package-json install-dir)

          deps
          (->> (get-deps-from-classpath)
               (resolve-conflicts)
               (remove #(is-installed? % package-json)))]

      (when (seq deps)
        (install-deps config deps)
        ))))
