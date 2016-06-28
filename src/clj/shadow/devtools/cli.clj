(ns shadow.devtools.cli
  (:require [shadow.cljs.build :as cljs]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec :as spec]
            [shadow.spec.build :as s-build]
            [shadow.devtools.server :as devtools]
            [shadow.cljs.node :as node]
            [shadow.cljs.umd :as umd]))

(spec/def ::config (spec/+ ::s-build/build))

(defn load-cljs-edn []
  (-> (io/file "cljs.edn")
      (slurp)
      (edn/read-string)))

(defn load-cljs-edn! []
  (let [input (load-cljs-edn)
        config (spec/conform ::config input)]
    (when (= config ::spec/invalid)
      (spec/explain ::config input)
      (throw (ex-info "invalid config" (spec/explain-data ::config input))))

    config
    ))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries depends-on module-config))
    state
    modules))

(defn- configure-browser-build [state]
  (let [config (::build state)
        {:keys [public-path public-dir modules] :as config}
        (merge default-browser-config config)]
    (-> state
        (cond->
          public-dir
          (cljs/set-build-options
            {:public-dir (io/file public-dir)})
          public-path
          (cljs/set-build-options
            {:public-path public-path}))
        (configure-modules modules)
        )))

(defn- configure-script-build [state]
  (let [{:keys [output-to main] :as config} (::build state)]
    (-> state
        (node/configure config))))

(defn- configure-library-build [state]
  (let [{:keys [exports] :as config} (::build state)]
    (-> state
        (umd/create-module exports config)
        )))

;; FIXME: spec for cli
(defn- pick-build [config [build-id & more :as args]]
  (if (nil? build-id)
    (first config)
    (let [id (keyword build-id)
          build
          (->> config
               (filter #(= id (:id %)))
               (first))]
      (when-not build
        (throw (ex-info (str "no build with id: " build-id) {:id id})))
      build
      )))

(defn- dev-setup [{:keys [dev] :as build}]
  (-> (cljs/init-state)
      (cljs/set-build-options {:use-file-min false})
      (cljs/enable-source-maps)
      (cond->
        dev
        (cljs/set-build-options dev))
      (cljs/find-resources-in-classpath)
      (assoc ::build build)
      ))

(defn once [& args]
  (let [config (load-cljs-edn!)
        build (pick-build config args)
        state (dev-setup build)]
    (case (:target build)
      :browser
      (-> state
          (configure-browser-build)
          (cljs/compile-modules)
          (cljs/flush-unoptimized))

      :script
      (-> state
          (configure-script-build)
          (node/compile)
          (node/flush-unoptimized))

      :library
      (-> state
          (configure-library-build)
          (cljs/compile-modules)
          (umd/flush-unoptimized-module)
          )))
  :done)

(def default-devtools-options
  {:console-support true})

(defn dev [& args]
  (let [config (load-cljs-edn!)
        {:keys [devtools] :as build} (pick-build config args)
        devtools (merge default-devtools-options devtools)
        state (dev-setup build)]
    (case (:target build)
      :browser
      (-> state
          (configure-browser-build)
          (devtools/start-loop devtools))
      :script
      (throw (ex-info "live-reload dev-mode not yet supported for node script" {}))
      :library
      (throw (ex-info "live-reload dev-mode not yet supported for node library" {}))
      ))
  :done)

(defn- release-setup [{:keys [release] :as build}]
  (-> (cljs/init-state)
      (cljs/set-build-options
        {:optimizations :advanced
         :pretty-print false})
      (cond->
        release
        (cljs/set-build-options release))
      (cljs/find-resources-in-classpath)
      (assoc ::build build)
      ))

(defn release [& args]
  (let [config (load-cljs-edn!)
        build (pick-build config args)
        state (release-setup build)]

    (case (:target build)
      :browser
      (-> state
          (configure-browser-build)
          (cljs/compile-modules)
          (cljs/closure-optimize)
          (cljs/flush-modules-to-disk))

      :script
      (-> state
          (configure-script-build)
          (assoc :optimizations :simple)
          (node/compile)
          (node/optimize)
          (node/flush))

      :library
      (-> state
          (configure-library-build)
          (assoc :optimizations :simple)
          (node/compile)
          (node/optimize)
          (umd/flush-module))))
  :done)

(defn- test-setup []
  (-> (cljs/init-state)
      (cljs/enable-source-maps)
      (cljs/set-build-options
        {:public-dir (io/file "target" "cljs-test")
         :public-path "target/cljs-test"})
      (cljs/find-resources-in-classpath)
      ))

(defn autotest
  [& args]
  (-> (test-setup)
      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cond->
                ;; first pass, run all tests
                (empty? modified)
                (node/execute-all-tests!)
                ;; only execute tests that might have been affected by the modified files
                (not (empty? modified))
                (node/execute-affected-tests! modified))
              )))))

(defn test-all []
  (-> (test-setup)
      (node/execute-all-tests!)
      ))

(defn test-affected [test-ns]
  (-> (test-setup)
      (node/execute-affected-tests! [(cljs/ns->cljs-file test-ns)])
      ))
