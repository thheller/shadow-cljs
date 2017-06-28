(ns shadow.cljs.npm.js-index
  "deps extractor loosely based on cljs/module_deps.js"
  (:require ["path" :as path]
            ["module-deps" :as mdeps]
            ["node-resolve" :as node-resolve]
            ["browser-resolve" :as browser-resolve]
            ["detective" :as detective]
            [cljs.pprint :refer (pprint)]
            [goog.object :as gobj]
            [shadow.json :as json]))

(defn add-package [{:keys [package files] :as state} pkg]
  (let [pkg-dir
        (gobj/get pkg "__dirname")

        main
        (some->>
          (gobj/get pkg "main")
          (path/join pkg-dir))

        {:keys [name] :as pkg}
        (-> (json/to-clj pkg)
            (select-keys [:name :version])
            (assoc :dir pkg-dir
                   :main main))]

    (-> state
        (cond->
          name ;; FIXME: are packages without name useful, can't use it to look up things
          (update :packages assoc name pkg)))))

(defn add-file [state file]
  (update state :files conj file))

(defn index-for-file
  "takes a file with require calls and prints an edn index structure for all deps
   and required packages"
  [base-dir entry]
  (let [state-ref
        (volatile!
          {:packages {}
           :missing #{}
           :files []
           :core #{}})

        opts
        #js {:filter
             (fn [name]
               (if-not (node-resolve/isCore name)
                 true
                 (do (vswap! state-ref update :core conj name)
                     false)))
             :ignoreMissing true}

        mdeps
        (mdeps opts)]

    (doto mdeps
      (.on "package" #(vswap! state-ref add-package %))
      (.on "file" #(vswap! state-ref add-file %))
      (.on "error" #(throw %1))
      (.on "missing" #(vswap! state-ref update :missing conj %))

      (.on "end"
        (fn []
          ;; don't ever want the input file in there
          ;; can't figure out a way to tell module-deps which packages to crawl directly
          ;; pretty annoying I have to go through a file
          (vswap! state-ref update :files subvec 1)
          ;; FIXME: don't actually need to pprint, just nicer too look at when debugging
          (pprint @state-ref)))

      (.end #js {:file entry
                 :basedir base-dir})
      ;; I don't understand this part?
      (.resume))))
