(ns shadow.bootstrap
  (:require-macros [cljs.core.async.macros :refer (go)])
  (:require [clojure.set :as set]
            [cljs.core.async :as async]
            [cljs.js :as cljs]
            [shadow.xhr :as xhr]
            [shadow.js]
            [cognitect.transit :as transit]
            [cljs.env :as env]))

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
          {:source-order (into [] (map :resource-id) sources)}
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

(defn load-analyzer-data [ns]
  {:pre [(symbol? ns)]}
  (let [{:keys [type source-name] :as ns-info} (get-ns-info ns)]
    (js/console.log "analyzer" ns ns-info)
    (if (not= :cljs type)
      empty-result-helper
      (transit-load (str asset-path "/ana/" source-name ".ana.transit.json")))))

(defn load-macro-js [ns]
  {:pre [(symbol? ns)]}
  (let [{:keys [output-name] :as ns-info} (get-ns-info ns)]

    (go (when-some [x (<! (txt-load (str asset-path "/js/" output-name)))]
          (js/console.log "macro-js" ns ns-info (count x) "bytes")
          x))))

(defn load-js [ns]
  (let [{:keys [output-name provides] :as ns-info}
        (get-ns-info ns)]

    (go (when-some [x (<! (txt-load (str asset-path "/js/" output-name)))]
          (js/console.log "js" ns ns-info (count x) "bytes")
          x))))

(defn init [init-cb]
  ;; FIXME: add goog-define to path
  ;; load /js/boostrap/index.transit.json
  ;; build load index
  ;; load cljs.core$macros
  ;; load cljs.core analyzer data + maybe others
  ;; call init-cb
  (let [ch (async/chan)]

    (go (when-some [data (<! (transit-load (str asset-path "/index.transit.json")))]
          (let [idx (build-index data)
                ana-core (<! (load-analyzer-data 'cljs.core))]
            (cljs/load-analysis-cache! compile-state-ref 'cljs.core ana-core)
            (js/eval (<! (load-macro-js 'cljs.core$macros)))
            (init-cb)
            )))))

(def loop-guard (atom 0))

(defn load [{:keys [name path macros] :as rc} cb]
  ;; check index build by init
  ;; find all dependencies
  ;; load js and ana data via xhr
  ;; maybe eval?
  ;; call cb

  (when (> (swap! loop-guard inc) 20)
    (js/console.log "boom" rc)
    (throw (ex-info "enough is enough" {})))

  (let [ns
        (if macros
          (symbol (str name "$macros"))
          name)

        {:keys [type] :as ns-info}
        (get-ns-info ns)]

    ;; FIXME: needs to ensure that deps are loaded first
    (go (let [ana (<! (load-analyzer-data ns))
              js (<! (load-js ns))]
          (js/console.log "boot/load" name macros ns ana (count js))
          (swap! cljs/*loaded* conj ns)
          (cb {:lang :js :source js :cache ana}))
        )))