(ns shadow.cljs.npm.create
  (:require
    ["child_process" :as cp]
    ["fs" :as fs]
    ["path" :as path]
    [goog.string :as gstr]
    [clojure.string :as str]
    [cljs.tools.cli :as cli]
    [cljs.pprint :refer (pprint)]
    [cljs.core.async :as async :refer (go)]
    [shadow.cli-util :refer (parse-args)]
    ))

(def cli-config
  {:aliases
   {"h" :help}

   :init-command :create

   :commands
   {:create {:args-mode :single}}})

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

(defn is-windows? []
  (str/includes? js/process.platform "win32"))

(defmethod run-action! ::execute
  [{:keys [project-root] :as state} {:keys [label command args]}]
  (println "----")
  (println label)
  (println)
  (let [^js result
        (cp/spawnSync
          command
          (into-array args)
          (-> {:stdio "inherit"
               :cwd project-root}
              (cond->
                (is-windows?)
                (assoc :shell true :windowsVerbatimArguments true))
              (clj->js)))]

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


(defn create-project [{:keys [arguments flags options] :as cli-args}]

  (let [[project-path] arguments
        project-root (path/resolve project-path)
        project-name (path/basename project-root)]

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

          (flush!)
          ))))

(defn main [& args]
  (let [{:keys [error] :as result}
        (parse-args cli-config args)]

    (if error
      (do (println (:msg error))
          (js/process.exit 1))

      (do (js/process.on "SIGINT"
            (fn []
              (swap! state-ref :abort true)))

          (create-project result)
          ))))