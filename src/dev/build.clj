(ns build
  (:require [shadow.cljs.build :as cljs]
            [shadow.devtools.server :as devtools]
            [shadow.devtools.nrepl :as nrepl]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [shadow.devtools.sass :as sass]))

(defn create-debug-launcher [{:keys [closure-defines] :as state}]
  (let [s (-> (slurp "client/panel.html")
              (str/replace #"<!-- ###MARKER### -->" (str "<script>shadow.devtools.client.activate('" (get closure-defines "shadow.devtools.url") "');</script>")))]
    (spit "client/launcher.html" s))
  state)

(def css-packages
  [{:name "main"
    :modules [(io/file "test-project/src/css/app.scss")]
    :public-dir (io/file "test-project/public/css")
    :public-path "css"}])

(defn css [& args]
  (sass/build-packages css-packages))

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
        {:css-packages css-packages}
        (fn [state modified]
          (-> state
              #_ (create-debug-launcher)
              (cljs/step-compile-modules)
              (cljs/flush-unoptimized))))))

(comment
  (nrepl/defmiddleware
    browser-dev-nrepl
    {}
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
        (cljs/step-finalize-config))
    (fn [state modified]
      (-> state
          (create-debug-launcher)
          (cljs/step-compile-modules)
          (cljs/flush-unoptimized)))))

(defn client-dev [& args]
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (assoc :optimizations :none
             :public-dir (io/file "client/js")
             :public-path "js")
      (cljs/step-find-resources-in-jars)
      (cljs/step-find-resources "src/cljs")
      (cljs/step-configure-module :client ['shadow.devtools.client] #{})
      (cljs/step-finalize-config)

      #_(live-reload/setup {:after-load 'shadow.devtools.client/restart
                            :before-load 'shadow.devtools.client/unload
                            :css-packages {"client" {:manifest "client/css/manifest.json"
                                                     :path "/client/css"}}})

      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cljs/step-compile-modules)
              (cljs/flush-unoptimized))))))

