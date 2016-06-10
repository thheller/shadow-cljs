(ns shadow.devtools.cli
  (:require [shadow.cljs.build :as cljs]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec :as spec]
            [shadow.spec.build :as s-build]
            [shadow.devtools.server :as devtools]))

(spec/def ::config (spec/+ ::s-build/build))

(def default-module-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn load-cljs-edn []
  (-> (io/file "cljs.edn")
      (slurp)
      (edn/read-string)))

(defn load-cljs-edn! []
  (let [input (load-cljs-edn)
        config (spec/conform ::config input)]
    (when (= config ::spec/invalid)
      (spec/explain ::config input)
      (throw (ex-info "invalid config" {})))

    config
    ))

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries depends-on module-config))
    state
    modules))

(defmulti configure-build (fn [state {:keys [target] :as build}] target))

(defmethod configure-build :browser [state config]
  (let [{:keys [public-path public-dir modules] :as config}
        (merge default-module-config config)]
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

(defn- pick-build [config args]
  (first config))

(defn- dev-setup [{:keys [dev] :as build}]
  (-> (cljs/init-state)
      (cljs/set-build-options {:use-file-min false})
      (cljs/enable-source-maps)
      (cond->
        dev
        (cljs/set-build-options dev))
      (cljs/find-resources-in-classpath)
      ))

(defn once [& args]
  (let [config (load-cljs-edn!)
        build (pick-build config args)]
    (-> (dev-setup build)
        (configure-build build)
        (cljs/compile-modules)
        (cljs/flush-unoptimized)))
  :done)

(defn dev [& args]
  (let [config (load-cljs-edn!)
        build (pick-build config args)]
    (-> (dev-setup build)
        (configure-build build)
        (devtools/start-loop
          {:console-support true})))
  :done)

(defn- release-setup [{:keys [release] :as build}]
  (-> (cljs/init-state)
      (cljs/set-build-options
        {:optimizations :advanced
         :pretty-print false})
      (cond->
        release
        (cljs/set-build-options release))
      (cljs/find-resources-in-classpath)))

(defn release [& args]
  (let [config (load-cljs-edn!)
        build (pick-build config args)]
    (-> (release-setup build)
        (configure-build build)
        (cljs/compile-modules)
        (cljs/closure-optimize)
        (cljs/flush-modules-to-disk)
        ))
  :done)

(defn release-debug [& args]
  ;; FIXME: too much duplicated code from release just to set some options
  (let [config (load-cljs-edn!)
        build (pick-build config args)]
    (-> (release-setup build)
        (cljs/set-build-options
          {:optimizations :advanced
           :pretty-print true
           :pseudo-names true})
        (configure-build build)
        (cljs/compile-modules)
        (cljs/closure-optimize)
        (cljs/flush-modules-to-disk)
        ))
  :done)
