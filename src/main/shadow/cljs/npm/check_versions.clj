(ns shadow.cljs.npm.check-versions
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.build.data :as data]
            [shadow.build.npm :as npm]
            [clojure.string :as str]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]
            [shadow.cljs.util :as util]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server.util :as sutil]
            [shadow.build :as build]))

(defn check-npm-versions [{::keys [version-checked] :keys [npm] :as state}]
  (let [pkg-index
        (->> (data/get-build-sources state)
             (filter #(= :shadow-js (:type %)))
             (map :package-name)
             (remove nil?)
             (remove #(contains? version-checked %))
             (into #{})
             (reduce
               (fn [m package-name]
                 (assoc m package-name (npm/find-package npm package-name)))
               {}))]

    (if-not (seq pkg-index)
      ;; prevent the extra verbose log entry when no check is done
      state
      ;; keeping track of what we checked so its not repeatedly check during watch
      ;; FIXME: updating npm package while watch is running will not check again
      (reduce
        (fn [state package-name]
          (doseq [[dep wanted-version]
                  (merge (get-in pkg-index [package-name :package-json "dependencies"])
                    (get-in pkg-index [package-name :package-json "peerDependencies"]))
                  ;; not all deps end up being used so we don't need to check the version
                  :when (get pkg-index dep)
                  :let [installed-version (get-in pkg-index [dep :package-json "version"])]
                  :when (and (not (str/includes? wanted-version "http:"))
                             (not (str/includes? wanted-version "https:"))
                             (not (str/includes? wanted-version "file:"))
                             (not (str/includes? wanted-version "github:")))
                  :when (not (npm-deps/semver-intersects wanted-version installed-version))]

            (format "npm package \"%s\" expected version \"%s@%s\" but \"%s\" is installed."
              package-name
              dep
              wanted-version
              installed-version))

          (update state ::version-checked util/set-conj package-name))
        state
        (keys pkg-index)))))

(defn -main [build-id]
  (let [build-id
        (keyword build-id)

        build-config
        (api/get-build-config build-id)

        opts
        {}

        build-state
        (api/with-runtime
          (-> (sutil/new-build build-config :release opts)
              (build/configure :release build-config opts)
              (build/resolve)))]

    (check-npm-versions build-state)))
