(ns shadow.bootstrap
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [clojure.set :as set]
            [cljs.core.async :as async]
            [cljs.js :as cljs]
            [shadow.xhr :as xhr]
            [shadow.js]
            [cognitect.transit :as transit]
            [cljs.env :as env])
  (:import [goog.net BulkLoader]))

(goog-define asset-path "/js/bootstrap")

(defn transit-read [txt]
  (let [r (transit/reader :json)]
    (transit/read r txt)))

(defn transit-load [path]
  (xhr/chan :GET path nil {:body-only true
                           :transform transit-read}))

(defn txt-load [path]
  (xhr/chan :GET path nil {:body-only true
                           :transform identity}))

(defonce empty-result-helper
  (doto (async/chan)
    (async/close!)))

(defonce compile-state-ref (env/default-compiler-env))

(defonce loaded-ref (atom #{}))

;; calls to this will be injected by shadow-cljs
;; it will receive an array of strings matching the goog.provide
;; names that where provided by the "app"
(defn set-loaded [namespaces]
  (let [loaded (into #{} (map symbol) namespaces)]
    (swap! loaded-ref set/union loaded)
    (swap! cljs/*loaded* set/union loaded)))

(defonce index-ref (atom nil))

(defn build-index [sources]
  (let [idx
        (reduce
          (fn [idx {:keys [resource-id] :as rc}]
            (assoc-in idx [:sources resource-id] rc))
          {:sources-ordered sources}
          sources)

        idx
        (reduce
          (fn [idx [provide resource-id]]
            (assoc-in idx [:sym->id provide] resource-id))
          idx
          (for [{:keys [resource-id provides]} sources
                provide provides]
            [provide resource-id]))]

    (reset! index-ref idx)

    (js/console.log "build-index" idx)

    idx))

(defn get-ns-info [ns]
  (let [idx @index-ref]
    (let [id (get-in idx [:sym->id ns])]
      (or (get-in idx [:sources id])
          (throw (ex-info (str "ns " ns " not available") {:ns ns}))
          ))))

(defn init [init-cb]
  ;; FIXME: add goog-define to path
  ;; load /js/boostrap/index.transit.json
  ;; build load index
  ;; call init-cb
  (let [ch (async/chan)]
    (go (when-some [data (<! (transit-load (str asset-path "/index.transit.json")))]
          (build-index data)

          ;; FIXME: this is ugly but we need to grab analyzer data for already loaded things
          ;; FIXME: actually load it all, not just core
          (let [core-ana (<! (transit-load (str asset-path "/ana/cljs.core.cljs.ana.transit.json")))]
            (cljs/load-analysis-cache! compile-state-ref 'cljs.core core-ana))
          (init-cb)
          ))))

(defn find-deps [entries]
  {:pre [(set? entries)
         (every? symbol? entries)]}
  ;; abusing that :sources-ordered is in correct dependency order
  ;; just walk in reverse and pick up everything along the way
  (->> (reverse (:sources-ordered @index-ref))
       (reduce
         (fn [{:keys [deps order] :as x} {:keys [resource-id output-name provides requires] :as src}]

           (cond
             ;; skip loading files that are loaded
             ;; FIXME: keep track of loaded resource-ids instead of the provides?
             (set/superset? @loaded-ref provides)
             x

             ;; don't load files that don't provide anything we want
             (not (seq (set/intersection deps provides)))
             x

             :else
             {:deps (set/union deps requires)
              :order (conj order src)}))
         {:deps entries
          :order []})
       (:order)
       (reverse)
       (into [])))

(defn execute-load! [{:keys [type text uri ns provides] :as load-info}]
  (js/console.log "load" type ns load-info)
  (case type
    :analyzer
    (let [data (transit-read text)]
      (cljs/load-analysis-cache! compile-state-ref ns data))
    :js
    (do (swap! loaded-ref set/union provides)
        (swap! cljs/*loaded* set/union provides)
        (js/eval text))
    ))

(defn load [{:keys [name path macros] :as rc} cb]
  ;; check index build by init
  ;; find all dependencies
  ;; load js and ana data via xhr
  ;; maybe eval?
  ;; call cb
  (let [ns
        (if macros
          (symbol (str name "$macros"))
          name)

        deps-to-load
        (find-deps #{ns})

        macro-deps
        (->> deps-to-load
             (filter #(= :cljs (:type %)))
             (map :macro-requires)
             (reduce set/union)
             (map #(symbol (str % "$macros")))
             (into #{}))

        ;; second pass due to circular dependencies in macros
        deps-to-load
        (find-deps (conj macro-deps ns))

        load-info
        (reduce
          (fn [load-info {:keys [ns provides type output-name source-name]}]
            (-> load-info
                (cond->
                  (= :cljs type)
                  (conj {:type :analyzer
                         :ns ns
                         :uri (str asset-path "/ana/" source-name ".ana.transit.json")}))
                (conj {:type :js
                       :ns ns
                       :provides provides
                       :uri (str asset-path "/js/" output-name)})))
          []
          deps-to-load)

        uris
        (into [] (map :uri) load-info)

        loader
        (BulkLoader. (into-array uris))]

    (js/console.log :load-fn ns load-info)

    (.listen loader js/goog.net.EventType.SUCCESS
      (fn [e]
        (let [texts (.getResponseTexts loader)]
          ;; FIXME: this should probably do something async
          ;; otherwise it will block the entire time 60 or so files
          ;; are eval'd or transit parsed
          (doseq [load (map #(assoc %1 :text %2) load-info texts)]
            (execute-load! load))

          (js/console.log "compile-state after load" @compile-state-ref)
          ;; callback with dummy so cljs.js doesn't attempt to load deps all over again
          (cb {:lang :js :source ""})
          )))

    (.load loader)
    ))