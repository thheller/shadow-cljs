(ns shadow.cljs.npm.create
  (:require
    ["child_process" :as cp]
    ["fs" :as fs]
    ["path" :as path]
    [cljs.tools.cli :as cli]
    [cljs.pprint :refer (pprint)]
    [cljs.core.async :as async :refer (go)]
    [goog.string :as gstr]
    [clojure.string :as str]))

(def cli-spec
  [["-p" "--pretend" "pretend only, don't run/create anything"]
   ["-h" "--help" "no help here"]])

(defonce state-ref
  (atom {:queue []}))

(defn queue! [action cmd]
  {:pre [(keyword? action)
         (map? cmd)]}
  (swap! state-ref update :queue conj (assoc cmd ::action action)))

(defmulti run-action! (fn [state action] (::action action)))

(defn next! []
  (let [{:keys [abort queue] :as state} @state-ref]
    (when (and (not abort) (seq queue))
      (let [next-item
            (-> state :queue first)

            next-state
            (update state :queue subvec 1)]

        (when-not (true? (:abort state))
          (reset! state-ref next-state)
          (run-action! next-state next-item))))))

(defn flush! []
  (if (get-in @state-ref [:options :pretend])
    (pprint @state-ref)

    (next!)
    ))

(defn make-dirs [path]
  (when-not (fs/existsSync path)
    (make-dirs (path/dirname path))
    (fs/mkdirSync path)
    ))

(defmethod run-action! ::create-file
  [{:keys [project-root] :as state} {:keys [name content]}]
  (let [full-path (path/resolve project-root name)]
    (println "Creating:" full-path)
    (make-dirs (path/dirname full-path))
    (fs/writeFileSync full-path content)
    (next!)
    ))

(defmethod run-action! ::create-dir
  [{:keys [project-root] :as state} {:keys [dir]}]
  (println "Creating:" dir)
  (make-dirs dir)
  (next!))

(defmethod run-action! ::execute
  [{:keys [project-root] :as state} {:keys [label command args]}]
  (println "----")
  (println label)
  (println)
  (let [^js result
        (cp/spawnSync
          command
          (into-array args)
          #js {:stdio "inherit"
               :cwd project-root})]

    (if-not (zero? (.-status result))
      (println "Command unsuccessful. Aborting ...")
      (next!))))

(defmethod run-action! ::done
  [{:keys [project-root] :as state} {:keys [label command args]}]
  (println "----")
  (println "Done. Actual project initialization will follow soon.")
  (println "----")
  (next!))

(defn add-package-json [project-name]
  (let [package
        #js {:name project-name
             :version "0.0.1"
             :private true
             :devDependencies #js {}
             :dependencies #js {}}

        package-json
        (js/JSON.stringify package js/undefined 2)]

    (queue! ::create-file
      {:name "package.json"
       :content (str package-json "\n")})))

(defn copy-file
  ([output-name]
   (copy-file output-name output-name))
  ([output-name template-name]
   (queue! ::create-file
     {:name output-name
      :content (str (fs/readFileSync (path/resolve js/__dirname ".." "files" template-name)))})))

(defn create-project [project-name args options]
  (let [project-root (path/resolve project-name)]
    (swap! state-ref assoc :project-root project-root)

    (if (fs/existsSync project-root)
      (do (println "Directory already exists!")
          (println project-root))

      (do (println "shadow-cljs - creating project:" project-root)

          (add-package-json project-name)
          (copy-file "shadow-cljs.edn")
          (copy-file ".gitignore" "gitignore")

          (queue! ::create-dir {:dir (path/resolve project-root "src" "main")})
          (queue! ::create-dir {:dir (path/resolve project-root "src" "test")})

          (queue! ::execute
            {:label "Installing shadow-cljs in project."
             :command "npm"
             :args ["install" "--save-dev" "--save-exact" "shadow-cljs"]})

          (queue! ::done {})

          #_(queue! ::execute
              {:label "Running shadow-cljs to initialize project."
               :command "npx"
               :args (into ["shadow-cljs" "run" "shadow.cljs.project/init"] args)})
          ))))

(defn main [& args]
  (let [{:keys [errors summary arguments options] :as parsed}
        (cli/parse-opts args cli-spec)

        [project-name & more-arguments]
        arguments]

    (js/process.on "SIGINT"
      (fn []
        (swap! state-ref :abort true)))

    (cond
      (seq errors)
      (do (println "Invalid Arguments:")
          (println)
          (doseq [err errors]
            (println err))
          (println)
          (println "Usage: npx create-cljs-project <project-name>")
          (println summary)
          (js/process.exit 1))

      (not (seq project-name))
      (do (println "Please specify a project name!")
          (println "Usage: npx create-cljs-project <project-name>")
          (js/process.exit 2))

      (seq more-arguments)
      (do (println "Too many arguments!")
          (prn more-arguments)
          (js/process.exit 3))

      :else
      (do (create-project project-name args options)
          (flush!)))))