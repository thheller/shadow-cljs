(ns shadow.cljs.npm.js-index
  "deps extractor loosely based on cljs/module_deps.js"
  (:require ["path" :as path]
            ["detective" :as detect]
            ["fs" :as fs]
            [clojure.string :as str]
            [cljs.pprint :refer (pprint)]
            [goog.object :as gobj]
            [goog.string.format]
            [goog.string :as gstr :refer (format)]
            [shadow.json :as json]
            [shadow.cljs.npm.util :as util]))

(defn is-relative? [entry]
  (str/starts-with? entry "."))

(defn is-absolute? [entry]
  (str/starts-with? entry "/"))

(declare index-entry)

(defn locate-module [{:keys [base-dir] :as state} module-name]
  (let [module-dir
        (path/resolve base-dir "node_modules" module-name)

        package-json
        (path/resolve module-dir "package.json")]

    (if-not (fs/existsSync package-json)
      (update state :missing conj module-name)
      (let [{:strs [dependencies name version main browser] :as package-json}
            (-> (util/slurp package-json)
                (json/read-str {:key-fn identity}))

            mod-info
            {:name name
             :module-dir module-dir
             :main main
             :browser browser
             :version version
             :deps (into #{} (keys dependencies))}]

        (update state :modules assoc module-name mod-info)
        ))))

(defn maybe-locate-module [{:keys [modules] :as state} module-name]
  (if (contains? modules module-name)
    state
    (locate-module state module-name)))

(defn test-file [dir file]
  (when file
    (let [path (path/resolve dir file)]
      (when (fs/existsSync path)
        path))))

(defn test-file-exts [{:keys [extensions]} dir file]
  (reduce
    (fn [_ ext]
      (when-let [path (test-file dir (str file ext))]
        (reduced path)))
    nil
    extensions))

(defn index-absolute-entry [state entry]
  (if-not (fs/existsSync entry)
    (update state :missing conj entry)
    (let [content
          (util/slurp entry)

          requires
          (into [] (detect content))]

      (-> state
          ;; (update :deps assoc entry requires)
          (util/reduce->
            (fn [state require]
              (index-entry state (path/dirname entry) require))
            requires)))))

(defn add-and-index-file [{:keys [indexed] :as state} file]
  (if (contains? indexed file)
    state
    (-> state
        (update :files conj file)
        (update :indexed conj file)
        (index-absolute-entry file))))

(defn index-relative-entry [state relative-to entry]
  (let [file
        (or (test-file relative-to entry)
            (test-file-exts state relative-to entry))]

    (if-not file
      (update state :missing conj entry)
      (add-and-index-file state file))))

(defn index-module-entry [state entry]
  (let [module-name
        (if-let [idx (str/index-of entry "/")]
          (subs entry 0 idx)
          entry)

        {:keys [modules] :as state}
        (maybe-locate-module state module-name)]

    (if (not (contains? modules module-name))
      (update state :missing conj entry)

      ;; module found, lookup file
      (let [{:keys [module-dir main]} (get modules module-name)]
        (cond
          ;; "react-dom"
          (= entry module-name)
          (let [file
                (or (test-file module-dir main)
                    (test-file-exts state module-dir "index"))]

            (if-not file
              (update state :errors conj (format "Could not find main for module %s" module-name))
              (add-and-index-file state file)))

          ;; "react-dom/server"
          :else
          (let [suffix ;; server
                (subs entry (inc (count module-name)))

                file
                (or (test-file module-dir suffix)
                    (test-file-exts state module-dir suffix))]

            (if-not file
              (update state :errors conj (format "Could not find module file %s" entry))
              (add-and-index-file state file)
              )))))))

(defn index-entry [state reference-path entry]
  (cond
    (is-relative? entry)
    (index-relative-entry state reference-path entry)

    (is-absolute? entry)
    (index-absolute-entry state entry)

    :else
    (index-module-entry state entry)))

(defn index-entries
  "takes a file with require calls and prints an edn index structure for all deps
   and required packages"
  [base-dir entries]

  (let [state
        (-> {:base-dir base-dir
             :extensions [".js" ".json"]
             :missing #{}
             :modules {}
             :resolved {}
             :indexed #{}
             :files []}
            (util/reduce->
              #(index-entry %1 base-dir %2)
              entries)
            (dissoc :indexed))]

    (pprint state)))
