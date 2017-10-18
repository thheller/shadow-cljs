(ns shadow.cljs.devtools.server.npm-deps
  "utility namespaces for installing npm deps found in deps.cljs files"
  (:require [clojure.edn :as edn]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]
            [shadow.cljs.devtools.server.util :as util]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

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
  ;; FIXME: actually resolve this based on versions if possible
  (when (not= a-version b-version)
    (prn [:npm-deps-conflict id :using :a])
    (prn [:a a-version a-url])
    (prn [:b b-version b-url]))
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

(defn install-deps [deps]
  (let [args
        (for [{:keys [id version]} deps]
          (str id "@" version))

        proc
        (-> (ProcessBuilder.
              (into-array
                ;; FIXME: add shadow-cljs.edn config option to allow using yarn
                (into ["npm" "install" "--save"] args)))
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

    (for [{:keys [url npm-deps]} deps
          [dep-id dep-version] npm-deps]
      {:id (dep->str dep-id)
       :version dep-version
       :url url})
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
      (do (when (and installed-version
                     (not= installed-version version))
            (prn [:deps-version-conflict installed-version version url]))
          true))))

;; FIXME: call this as part of server startup?
(defn main []
  (let [package-json
        (read-package-json)

        deps
        (->> (get-deps-from-classpath)
             (resolve-conflicts)
             (remove #(is-installed? % package-json)))]

    (when (seq deps)
      (install-deps deps)
      )))

(defn -main []
  (main))

(comment
  (get-deps-from-classpath)

  (resolve-conflicts [{:id "react" :version "^15.0.0" :url "a"}
                      {:id "react" :version "^16.0.0" :url "b"}])

  (let [pkg {"dependencies" {"reactx" "^16.0.0"}}]
    (is-installed? {:id "react" :version "^16.0.0" :url "a"} pkg))

  (install-deps [{:id "react" :version "^15.0.0"}
                 {:id "react-dom" :version "^15.0.0"}]))
