(ns shadow.build.data
  "generic helpers for the build data structure"
  (:require [shadow.build.resource :as rc]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:import (java.net URL)))

;; FIXME: there are still lots of places that work directly with the map
;; that is ok for most things but makes it really annoying to change the structure of the data
;; this is basically just trying to create a formal API for the data
(defn build-state? [state]
  (true? (::build-state state)))

(def empty-data
  {::build-state true
   ;; map of {[ns require] sym}
   ;; keeping entries per ns since they might be relative
   ;; so the same alias might have different requires
   :string->sym {}

   ;; map of {sym resource-id}
   ;; CLJS or goog namespaces to source id
   :sym->id {}

   ;; lookup index of source name to source id
   ;; since closure only works with names not ids
   :name->id {}

   ;; map of {resource-id #{resource-id ...}}
   ;; keeps track of immediate deps for each given source
   ;; used for cache invalidation and collecting all deps
   :immediate-deps {}

   ;; map of {clojure.spec.alpha cljs.spec.alpha}
   ;; alias -> actual
   :ns-aliases {}

   ;; map of {cljs.spec.alpha clojure.spec.alpha}
   ;; actual -> alias, only needed by compiler for the extra provide it needs to account for
   :ns-aliases-reverse {}

   ;; a set to keep track of symbols that should be strings since they will never be compiled
   :magic-syms #{}

   ;; symbols provided by generated namespaces that do not have a file
   :virtual-provides {}

   ;; resource-id -> rc, will be copied to :sources when used
   ;; extra map so it doesn't conflict with resolve results
   :virtual-sources {}
   })

(defn init [state]
  (merge state empty-data))

(defn add-provide [state resource-id provide-sym]
  {:pre [(symbol? provide-sym)]}
  ;; sanity check, should never happen
  (let [conflict (get-in state [:sym->id provide-sym])]
    (when (and conflict (not= conflict resource-id))
      (throw (ex-info (format "symbol %s already provided by %s, conflict with %s" provide-sym conflict resource-id)
               {:provide provide-sym
                :conflict conflict
                :resource-id resource-id}))))

  (update state :sym->id assoc provide-sym resource-id))

(defn add-provides [{:keys [sources] :as state} {:keys [resource-id provides]}]
  {:pre [(rc/valid-resource-id? resource-id)
         (set? provides)
         ;; sanity check that source is added first
         (contains? sources resource-id)]}
  (reduce
    #(add-provide %1 resource-id %2)
    state
    provides))

(defn add-string-lookup [{:keys [sources] :as state} require-from-ns require sym]
  {:pre [(symbol? require-from-ns)
         (string? require)
         (symbol? sym)]}

  #_(when-not (contains? sources require-from-ns)
      (throw (ex-info (format "can't add string lookup \"%s\"->\"%s\" for non-existent source %s" require sym require-from-ns)
               {:require-from-id require-from-ns
                :require require
                :sym sym})))

  ;; FIXME: properly identify what :js-module-index is supposed to be
  ;; this needs to export any aliases we make so (:require ["some-package" :as x]) (x) works
  ;; "some-package" will be aliased to something and if it just exports a function we need to be
  ;; able to call it. CLJS does not allow calling aliases directly, so we only do this for strings
  ;; this is independent from the :requires alias we emit in the ns form
  (-> state
      (update-in [:compiler-env :js-module-index] assoc (str sym) sym)
      (update :string->sym assoc [require-from-ns require] sym)))

(defn get-string-alias [state require-from-ns require]
  {:pre [(symbol? require-from-ns)
         (string? require)]}
  (or (get-in state [:string->sym [require-from-ns require]])
      (throw (ex-info (format "could not find string alias for \"%s\" from %s" require require-from-ns)
               {:require-from-id require-from-ns
                :require require}))))

(defn get-deps-for-id
  "returns all deps as a set for a given id, these are unordered only a helper for caching"
  [state result rc-id]
  {:pre [(set? result)
         (rc/valid-resource-id? rc-id)]}
  (if (contains? result rc-id)
    result
    (let [deps (get-in state [:immediate-deps rc-id])]
      (when-not deps
        (throw (ex-info (format "no immediate deps for %s" rc-id) {})))
      (reduce #(get-deps-for-id state %1 %2) (conj result rc-id) deps))))

(defn deps->syms [{:keys [ns-aliases] :as state} {:keys [resource-id deps] :as rc}]
  (for [dep deps]
    (cond
      (symbol? dep)
      (get ns-aliases dep dep)

      (string? dep)
      (or (get-in state [:string->sym [(:ns rc) dep]])
          (throw (ex-info (format "no ns alias for dep: %s from: %s" dep resource-id) {:resource-id resource-id :dep dep})))

      :else
      (throw (ex-info "invalid dep" {:dep dep}))
      )))

(defn get-source-by-id [state id]
  {:pre [(rc/valid-resource-id? id)]}
  (or (get-in state [:virtual-sources id])
      (get-in state [:sources id])
      (throw (ex-info (format "no source by id: %s" id) {:id id}))))

(defn get-source-by-name [state name]
  {:pre [(string? name)]}
  (let [id (or (get-in state [:name->id name])
               (throw (ex-info (format "no source by name: %s" name) {:name name})))]
    (get-source-by-id state id)))

(defn get-source-by-provide [state provide]
  {:pre [(symbol? provide)]}
  (let [id (or (get-in state [:virtual-provides provide])
               (get-in state [:sym->id provide])
               (throw (ex-info (format "no source by provide: %s" provide) {:provide provide})))]
    (get-source-by-id state id)))

(defn get-output! [state {:keys [resource-id] :as rc}]
  (or (get-in state [:output resource-id])
      (throw (ex-info (format "no output for id: %s" resource-id) {:resource-id resource-id}))))

(defn add-source [state {:keys [resource-id resource-name] :as rc}]
  {:pre [(rc/valid-resource? rc)]}
  (-> state
      (update :sources assoc resource-id rc)
      (add-provides rc)
      (cond->
        resource-name
        (update :name->id assoc resource-name resource-id)
        )))

(defn maybe-add-source
  "add given resource to the :sources and lookup indexes"
  [{:keys [sources] :as state}
   {:keys [resource-id name] :as rc}]
  (if (contains? sources resource-id)
    ;; a source may already be present in case of string requires as they are not unique
    ;; "../foo" and "../../foo" may resolve to the same resource
    state
    (add-source state rc)
    ))

(defn merge-virtual-provides [current {:keys [resource-id provides] :as rc}]
  (reduce
    (fn [provide-map provide-sym]
      ;; FIXME: what about strings?
      (assert (symbol? provide-sym))

      (let [conflict (get provide-map provide-sym)]
        (when (and conflict (not= conflict resource-id))
          (throw (ex-info (format "virtual provide %s by %s already provided by %s" provide-sym resource-id conflict)
                   {:provide provide-sym
                    :conflict-with conflict
                    :resource-id resource-id})))

        (assoc provide-map provide-sym resource-id)))
    current
    provides))

(defn output-file [state name & names]
  (let [output-dir (get-in state [:build-options :output-dir])]
    (when-not output-dir
      (throw (ex-info "no :output-dir" {})))

    (apply io/file output-dir name names)))

(defn cache-file [{:keys [cache-dir] :as state} name & names]
  (when-not cache-dir
    (throw (ex-info "no :cache-dir" {})))

  (apply io/file cache-dir name names))

(defn url-last-modified [^URL url]
  (with-open [con (.openConnection url)]
    (.getLastModified con)))

(defn relative-path-from-dir [output-dir src-file rel-path]
  (let [output-dir
        (-> output-dir
            (.getCanonicalFile)
            (.toPath))

        src-file
        (-> src-file
            (.getParentFile))

        rel-file
        (-> (io/file src-file rel-path)
            (.getCanonicalFile)
            (.toPath))]

    (-> (.relativize output-dir rel-file)
        (.toString)
        ;; FIXME: need to keep it relative
        ;; should come up with a better representation, maybe a protocol?
        ;; {:type :module :package "react-dom" :suffix "server"}
        ;; {:type :relative :path "src/main/foo"}
        (->> (str "./"))
        )))