(ns shadow.build.modules
  (:refer-clojure :exclude (compile))
  (:require [clojure.set :as set]
            [shadow.cljs.util :as util]
            [shadow.build.resolve :as res]
            [shadow.build.compiler :as impl]
            [shadow.build.resource :as rc]
            [shadow.build.data :as data]
            [clojure.java.io :as io]
            [shadow.build.classpath :as cp]))

(defn topo-sort-modules*
  [{:keys [modules deps visited] :as state} module-id]
  (let [{:keys [depends-on] :as mod}
        (get modules module-id)]
    (cond
      (nil? mod)
      (throw (ex-info "module not defined" {:missing module-id}))

      (contains? deps module-id)
      (throw (ex-info "module circular dependency" {:deps deps :module-id module-id}))

      (contains? visited module-id)
      state

      :else
      (-> state
          (update :visited conj module-id)
          (update :deps conj module-id)
          (as-> state
            (reduce topo-sort-modules* state depends-on))
          (update :deps disj module-id)
          (update :order conj module-id)))))

(defn topo-sort-modules
  "sorts the :modules map, returns a vector of keywords in sorted order"
  [modules]
  (let [{:keys [deps visited order] :as result}
        (reduce
          topo-sort-modules*
          {:deps #{}
           :visited #{}
           :order []
           :modules modules}
          (keys modules))]

    (assert (empty? deps))
    (assert (= (count visited) (count modules)))

    order))

(defn compact-build-modules
  "sorts modules in dependency order and remove sources provided by parent deps"
  [{::keys [modules module-order] :as state}]

  ;; if only one module is defined we dont need all this work
  (if (= 1 (count modules))
    (let [mod-id (ffirst modules)]
      (update-in state [::modules mod-id] assoc :goog-base true))
    ;; else: multiple modules must be sorted in dependency order
    (let [src-refs
          (->> (for [mod-id module-order
                     :let [{:keys [sources depends-on] :as mod} (get modules mod-id)]
                     src sources]
                 [src mod-id])
               (reduce
                 (fn [src-refs [src dep]]
                   (update src-refs src util/set-conj dep))
                 {}))

          ;; could be optimized
          find-mod-deps
          (fn find-mod-deps [mod-id]
            (let [{:keys [module-id depends-on] :as mod}
                  (get modules mod-id)]
              (reduce set/union (into #{module-id} depends-on) (map find-mod-deps depends-on))))

          find-closest-common-dependency
          (fn [src deps]
            (let [all
                  (map #(find-mod-deps %) deps)

                  common
                  (apply set/intersection all)]
              (condp = (count common)
                0
                (throw (ex-info "no common dependency found for src" {:src src :deps deps}))

                1
                (first common)

                (->> module-order
                     (reverse)
                     (drop-while #(not (contains? common %)))
                     (first)))))

          all-sources
          (->> module-order
               (mapcat #(get-in modules [% :sources]))
               (distinct)
               (into []))

          final-sources
          (reduce
            (fn [final src]
              (let [deps
                    (get src-refs src)

                    target-mod
                    (if (= 1 (count deps))
                      (first deps)
                      (let [target-mod (find-closest-common-dependency src deps)]

                        ;; only warn when a file is moved to a module it wouldn't be in naturally
                        (when-not (contains? deps target-mod)
                          (util/log state {:type :module-move
                                           :src src
                                           :deps deps
                                           :moved-to target-mod}))
                        target-mod))]

                (update final target-mod util/vec-conj src)))
            {}
            all-sources)]

      (reduce
        (fn [state mod-id]
          (let [sources (get final-sources mod-id)]
            (when (empty? sources)
              (util/log state {:type :empty-module :mod-id mod-id}))

            (let [foreign-count
                  (->> sources
                       (map #(get-in state [:sources %]))
                       (filter util/foreign?)
                       (count))

                  all-foreign?
                  (= foreign-count (count sources))

                  ;; FIXME: this is a terrible hack to ensure that we know which module contains goog/base.js
                  ;; can't alias due to cyclic dependency, its an ordered vector, can't contains?
                  goog-base?
                  (some #(= % [:shadow.build.classpath/resource "goog/base.js"]) sources)

                  update
                  (-> {:sources sources
                       :foreign-count foreign-count}
                      (cond->
                        all-foreign?
                        (assoc :all-foreign all-foreign?)
                        goog-base?
                        (assoc :goog-base true)))]

              (update-in state [::modules mod-id] merge update)
              )))
        state
        module-order)
      )))


(defn add-module-pseudo-rc [state mod-pos mod-id js]
  (let [resource-id
        [mod-pos mod-id]

        provide
        (symbol (str "shadow.module." (name mod-id) "." (name mod-pos)))

        resource-name
        (str "shadow/module/" (name mod-id) "/" (name mod-pos) ".js")

        rc
        {:resource-id resource-id
         :type :goog
         :cache-key resource-id
         :last-modified 0
         :resource-name resource-name
         :output-name (util/flat-filename resource-name)
         :ns provide
         :provides #{provide}
         :requires #{}
         :deps []
         :source js
         :virtual true}]

    (-> state
        (data/add-source rc)
        (cond->
          (= ::prepend mod-pos)
          (update-in [::modules mod-id :sources] #(into [resource-id] %))

          (= ::append mod-pos)
          (update-in [::modules mod-id :sources] conj resource-id)
          ))))

(defn analyze-module
  "resolve all deps for a given module, based on specified :entries
   will update state for each module with :sources, a list of sources needed to compile this module
   will add pseudo-resources if :append-js or :prepend-js are present"
  [state module-id]
  {:pre [(data/build-state? state)
         (keyword? module-id)]}

  (let [{:keys [entries append-js prepend-js] :as module}
        (get-in state [::modules module-id])

        [sources state]
        (res/resolve-entries state entries)]

    (-> state
        (assoc-in [::modules module-id :sources] sources)
        (cond->
          (seq prepend-js)
          (add-module-pseudo-rc ::prepend module-id prepend-js)

          ;; bootstrap needs to append some load info
          ;; this ensures that the rc is append correctly
          ;; it will be modified by shadow.build.bootstrap
          (or (seq append-js) (:force-append module))
          (add-module-pseudo-rc ::append module-id (or append-js ""))
          ))))

(defn analyze-modules [{::keys [module-order] :as state}]
  (reduce analyze-module state module-order))

(defn get-modules-ordered
  [{::keys [modules module-order] :as state}]
  (->> module-order
       (map #(get modules %))
       (into [])))

(defn normalize-config [config]
  (reduce-kv
    (fn [m module-id mod]
      (assert (keyword? module-id))
      (assert (map? mod))
      (let [module-name
            (str (name module-id) ".js")

            mod (assoc mod
                  :module-id module-id
                  :module-name module-name
                  :output-name module-name)]
        (assoc m module-id mod)))
    {}
    config))

(defn configure [state config]
  (let [modules (normalize-config config)]
    (assoc state ::config modules)))

(defn configured? [state]
  (contains? state ::config))

(defn set-build-info [state]
  (let [build-modules
        (get-modules-ordered state)

        build-sources
        (->> build-modules
             (mapcat :sources)
             (distinct) ;; FIXME: should already be distinct
             (into []))]

    (assoc state
      :build-modules build-modules
      :build-sources build-sources)))

(defn analyze
  "prepares :modules for compilation (sort and compacts duplicate sources)"
  [{::keys [config] :as state}]

  (let [module-order (topo-sort-modules config)]
    (-> state
        (assoc
          ::modules config
          ::module-order module-order)
        (analyze-modules)
        (compact-build-modules)
        (set-build-info)
        )))

