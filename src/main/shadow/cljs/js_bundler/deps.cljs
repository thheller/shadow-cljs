(ns shadow.cljs.js-bundler.deps
  "deps extractor losely based on cljs/module_deps.js"
  (:require ["path" :as path]
            ["module-deps" :as mdeps]
            ["node-resolve" :as node-resolve]
            ["browser-resolve" :as browser-resolve]
            [cljs.pprint :refer (pprint)]
            [goog.object :as gobj]
            [shadow.json :as json]))

(defn as-rel-path [state path]
  (path/relative "." path))

(defn add-package [{:keys [package files] :as state} pkg]
  (let [pkg-dir
        (as-rel-path state (gobj/get pkg "__dirname"))

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
  (update state :files conj (as-rel-path state file)))

(defn main [entry]
  (let [opts
        #js {:filter #(not (node-resolve/isCore %))}

        mdeps
        (mdeps opts)

        state-ref
        (volatile!
          {:packages {}
           :files []})]

    (doto mdeps
      (.on "package" #(vswap! state-ref add-package %))
      (.on "file" #(vswap! state-ref add-file %))
      (.on "end"
        (fn []
          ;; don't ever want the input file in there
          ;; can't figure out a way to tell module-deps which packages to crawl directly
          ;; pretty annoying I have to go through a file
          (vswap! state-ref update :files subvec 1)
          ;; FIXME: don't actually need to pprint, just nicer too look at when debugging
          (pprint @state-ref)))

      (.end #js {:file (path/resolve entry)})
      ;; I don't understand this part?
      (.resume))))
