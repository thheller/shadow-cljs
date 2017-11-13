(ns shadow.build.data
  "generic helpers for the build data structure"
  (:require [shadow.build.resource :as rc]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import (java.net URL)))

;; FIXME: there are still lots of places that work directly with the map
;; that is ok for most things but makes it really annoying to change the structure of the data
;; this is basically just trying to create a formal API for the data
(defn build-state? [state]
  (true? (::build-state state)))

(def empty-data
  {::build-state true
   ;; map of {ns {require sym}}
   ;; keeping entries per ns since they might be relative
   ;; so the same alias might have different requires
   :str->sym {}

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

   ;; set of namespaces that are actually in use after :simple optimizations
   ;; some condition requires are removed, eg. react v16 bundle style
   ;; and those should not be included in the final output
   :live-js-deps #{}

   ;; set of dead js resource ids
   :dead-js-deps #{}

   :js-entries #{}

   :mode :dev})

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

(defn remove-provides [state {:keys [provides] :as rc}]
  {:pre [(rc/valid-resource? rc)
         (set? provides)]}

  (reduce
    #(update %1 :sym->id dissoc %2)
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

  (assoc-in state [:str->sym require-from-ns require] sym))

(defn get-string-alias [state require-from-ns require]
  {:pre [(symbol? require-from-ns)
         (string? require)]}
  (or (get-in state [:str->sym require-from-ns require])
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
  (into [] (for [dep deps]
             (cond
               (symbol? dep)
               (get ns-aliases dep dep)

               (string? dep)
               (or (get-in state [:str->sym (:ns rc) dep])
                   (throw (ex-info (format "no ns alias for dep: %s from: %s" dep resource-id) {:resource-id resource-id :dep dep})))

               :else
               (throw (ex-info "invalid dep" {:dep dep}))
               ))))

(defn get-source-by-id [state id]
  {:pre [(rc/valid-resource-id? id)]}
  (or (get-in state [:sources id])
      (throw (ex-info (format "no source by id: %s" id) {:id id}))))

(defn get-source-by-name [state name]
  {:pre [(string? name)]}
  (let [id (or (get-in state [:name->id name])
               (throw (ex-info (format "no source by name: %s" name) {:name name})))]
    (get-source-by-id state id)))

(defn get-source-id-by-provide [state provide]
  {:pre [(symbol? provide)]}
  (or (get-in state [:sym->id provide])
      (throw (ex-info (format "no source by provide: %s" provide) {:provide provide}))))

(defn get-source-by-provide [state provide]
  (let [id (get-source-id-by-provide state provide)]
    (get-source-by-id state id)))

(defn get-output! [state {:keys [resource-id] :as rc}]
  {:pre [(map? rc)
         (rc/valid-resource-id? resource-id)]}
  (or (get-in state [:output resource-id])
      (throw (ex-info (format "no output for id: %s" resource-id) {:resource-id resource-id}))))

(defn get-reader-features [state]
  (set/union
    (let [x (get-in state [:compiler-options :reader-features])]
      ;; FIXME: should probably validate this before starting a compile
      (cond
        (nil? x)
        #{}
        (keyword? x)
        #{x}
        (and (set? x) (every? keyword? x))
        x
        :else
        (throw (ex-info "invalid :reader-features" {:tag ::invalid-reader-features
                                                    :x x}))))
    #{:cljs}))

(defn add-source [state {:keys [resource-id resource-name] :as rc}]
  {:pre [(rc/valid-resource? rc)]}
  (-> state
      (update :sources assoc resource-id rc)
      (add-provides rc)
      (cond->
        resource-name
        (update :name->id assoc resource-name resource-id)
        )))

(defn remove-source-by-id [state resource-id]
  (let [rc (get-in state [:sources resource-id])]
    (if-not rc
      state
      (-> state
          (remove-provides rc)
          (update :output dissoc resource-id)
          (update :immediate-deps dissoc resource-id)
          (update :sources dissoc resource-id)
          ))))

(defn overwrite-source
  "adds a source to the build state, if the ns was provided previously the other is removed"
  [state {:keys [ns resource-id] :as rc}]
  {:pre [(rc/valid-resource? rc)]}
  (let [other-id (get-in state [:sym->id ns])]
    (-> state
        (cond->
          other-id
          (remove-source-by-id other-id))
        (add-source rc))))

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



(defn js-names-accessed-from-cljs
  ([{:keys [build-sources] :as state}]
    (js-names-accessed-from-cljs state build-sources))
  ([{:keys [js-entries] :as state} build-sources]
   (let [all-names
         (->> (for [src-id build-sources
                    :let [{:keys [resource-id type] :as src}
                          (get-source-by-id state src-id)]
                    :when (not= :npm type)
                    :let [syms (deps->syms state src)]
                    sym syms]
                sym)
              (into #{}))]

     ;; filter out the names provided by npm deps
     (->> build-sources
          (map #(get-source-by-id state %))
          (filter #(= :npm (:type %)))
          (filter #(set/superset? all-names (:provides %)))
          (map :ns)
          (into js-entries)))))

(defn get-source-code
  "this loads the source code for each source or uses the current if already loaded
   everything should only ever access :source from the compiler resources and never access the
   filesystem again (since it may have changed)

   the loading is delayed until here because of the :foreign which may have a minified file
   that should be used for release builds"
  [state {:keys [resource-id type source-fn source file url] :as rc}]
  (or source

      ;; dynamic resources that decide their contents based on build state
      ;; ie. js includes that choose to provide minified or dev versions
      (when source-fn
        (source-fn state))

      ;; FIXME: foreign lib support removed?
      ;; foreign is special case because it may have url-min as well as url
      #_(when (= :foreign type)
          (let [use-file-min
                (not= :none (get-in state [:compiler-options :optimizations] :none))]
            (if (and use-file-min url-min)
              (slurp url-min)
              (slurp url))))

      ;; otherwise read the file
      (when file
        (slurp file))

      ;; or url fallback when no file exists (files in jar)
      (when url
        (slurp url))

      (throw (ex-info (format "failed to get code for %s" resource-id) rc))))