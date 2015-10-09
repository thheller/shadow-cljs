(ns build
  (:import [shadow.util FileWatcher])
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.live-reload :as live-reload]
            [shadow.devtools.server :as devtools]
            [shadow.sass :as sass]
            [clojure.java.io :as io]
            [clojure.string :as str]))


(def css-dir (io/file "src" "css"))

(def css-packages
  {"client" ["client"]})

(defn css []
  (.mkdirs (io/file "client" "css"))
  (doseq [[package-name package-mods] css-packages
          :let [scss-files (->> package-mods
                                (map #(str % ".scss"))
                                (map #(io/file css-dir %))
                                (into []))]]
    (println (format "CSS: Building package \"%s\"" package-name))
    (sass/build-with-manifest
     scss-files 
     (io/file "client" "css")
     )))


(defn watch-css []
  (let [watcher (FileWatcher/create css-dir ["scss"])]
    (loop []
      (try
        (css)
        (catch clojure.lang.ExceptionInfo ex
          (prn [:css-unhappy ex])))
      ;; FIXME: should only compile modules that were affected by changes
      ;; for now we don't actually care what changed, just recompile everything
      (doseq [[path change] (.waitForChanges watcher)]
        (println (format "CSS: %s -> %s" change path)))
      (recur)
      )))

(defn watch-css! []
  (doto (Thread. watch-css)
    (.setDaemon true)
    (.start)
    ))

(defn create-debug-launcher [{:keys [closure-defines] :as state}]
  (let [s (-> (slurp "client/panel.html")
              (str/replace #"<!-- ###MARKER### -->" (str "<script>shadow.devtools.client.activate('" (get closure-defines "shadow.devtools.url") "');</script>")))]
    (spit "client/launcher.html" s))
  state)

(defn browser-dev [& args]
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (assoc :optimizations :none
             :pretty-print true
             :public-dir (io/file "test-project/public/js")
             :public-path "js")
      (cljs/step-find-resources-in-jars)
      (cljs/step-find-resources "src/cljs")
      (cljs/step-find-resources "test-project/src/cljs")
      (cljs/step-configure-module :test ['test.app] #{})
      (cljs/step-finalize-config)

      (devtools/start-loop
        {}
        (fn [state modified]
          (-> state
              (create-debug-launcher)
              (cljs/step-compile-modules)
              (cljs/flush-unoptimized))))))

(defn client-dev [& args]
  (watch-css!)
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (assoc :optimizations :none
             :public-dir (io/file "client/js")
             :public-path "js")
      (cljs/step-find-resources-in-jars)
      (cljs/step-find-resources "src/cljs")
      (cljs/step-configure-module :client ['shadow.devtools.client] #{})
      (cljs/step-finalize-config)

      #_ (live-reload/setup {:after-load 'shadow.devtools.client/restart
                          :before-load 'shadow.devtools.client/unload
                          :css-packages {"client" {:manifest "client/css/manifest.json"
                                                   :path "/client/css"}}})

      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cljs/step-compile-modules)
              (cljs/flush-unoptimized))))))

