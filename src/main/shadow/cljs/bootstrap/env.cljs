(ns shadow.cljs.bootstrap.env
  (:require [clojure.set :as set]))

(defonce loaded-ref (atom #{}))

;; calls to this will be injected by shadow-cljs
;; it will receive an array of strings matching the goog.provide
;; names that where provided by the "app"
(defn set-loaded [namespaces]
  (let [loaded (into #{} (map symbol) namespaces)]
    (swap! loaded-ref set/union loaded)))

(defonce index-ref (atom nil))

(defn build-index [{:keys [sources exclude] :as data}]
  (let [idx
        (reduce
          (fn [idx {:keys [resource-id] :as rc}]
            (assoc-in idx [:sources resource-id] rc))
          {:sources-ordered sources
           :exclude exclude}
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

    #_ (js/console.log "build-index" idx)

    idx))

(defn get-ns-info [ns]
  (let [idx @index-ref
        id (get-in idx [:sym->id ns])]
    (or (get-in idx [:sources id])
        (throw (ex-info (str "ns " ns " not available") {:ns ns}))
        )))

(defn find-deps [entries]
  {:pre [(set? entries)
         (every? symbol? entries)]}
  ;; abusing that :sources-ordered is in correct dependency order
  ;; just walk in reverse and pick up everything along the way
  (->> (reverse (:sources-ordered @index-ref))
       (reduce
         (fn [{:keys [deps order] :as x} {:keys [resource-id output-name provides requires] :as src}]

           (cond
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
