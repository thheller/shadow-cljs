(ns shadow.devtools.server.compiler
  (:refer-clojure :exclude (compile flush))
  (:require [clojure.java.io :as io]
            [shadow.cljs.build :as cljs]
            [shadow.devtools.server.compiler.protocols :as p]
            [shadow.devtools.server.compiler.browser]
            [shadow.devtools.server.compiler.script]
            [shadow.devtools.server.compiler.library]
            ))

(defn- update-build-info-from-modules
  [{:keys [build-modules] :as state}]
  (update state ::build-info merge {:modules build-modules}))

(defn extract-build-info [state]
  (let [source->module
        (reduce
          (fn [index {:keys [sources name]}]
            (reduce
              (fn [index source]
                (assoc index source name))
              index
              sources))
          {}
          (:build-modules state))

        compiled-sources
        (into #{} (cljs/names-compiled-in-last-build state))

        build-sources
        (->> (:build-sources state)
             (map (fn [name]
                    (let [{:keys [js-name warnings] :as rc}
                          (get-in state [:sources name])]
                      {:name name
                       :js-name js-name
                       :module (get source->module name)})))
             (into []))]

    {:sources
     build-sources
     :compiled
     compiled-sources
     :warnings
     (->> (for [name (:build-sources state)
                :let [{:keys [warnings] :as src}
                      (get-in state [:sources name])]
                warning warnings]
            (assoc warning :source-name name))
          (into []))
     }))

(defn- update-build-info-after-compile
  [state]
  (update state ::build-info merge (extract-build-info state)))

(defn init
  ([mode config]
   (init mode config {}))
  ([mode {:keys [id target] :as config} init-options]
   {:pre [(keyword? mode)
          (keyword? id)
          (keyword? target)
          (map? config)
          (map? init-options)]
    :post [(cljs/compiler-state? %)]}

   (let [compiler
         (p/make-compiler config mode)

         state
         (-> (cljs/init-state)
             (assoc :cache-dir (io/file "target" "shadow-cache" (name id) (name mode))
                    ::p/compiler compiler)
             (merge init-options))

         state
         (-> (p/compile-init compiler state)
             (cljs/find-resources-in-classpath))]

     state
     )))

(defn compile
  [{::p/keys [compiler] :as state}]
  {:pre [(cljs/compiler-state? state)
         (satisfies? p/ICompile compiler)]
   :post [(cljs/compiler-state? %)]}

  (let [state
        (-> (p/compile-pre compiler state)
            (assoc ::build-info {})
            (cljs/prepare-compile)
            (cljs/prepare-modules)
            (update-build-info-from-modules)
            (cljs/do-compile-modules)
            (update-build-info-after-compile))]

    (p/compile-post compiler state)
    ))

(defn flush
  [{::p/keys [compiler] :as state}]
  {:pre [(cljs/compiler-state? state)
         (satisfies? p/ICompile compiler)]
   :post [(cljs/compiler-state? %)]}
  (p/compile-flush compiler state))


