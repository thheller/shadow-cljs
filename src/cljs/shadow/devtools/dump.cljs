(ns shadow.devtools.dump
  (:require [clojure.string :as str]
            [shadow.util :refer (log)]
            [shadow.dom :as dom]
            [shadow.object :as so]))

(so/define ::click-to-copy
  :dom/events [:click (fn [{:keys [value] :as this} e]
                        (log "clicked this" (pr-str value)))])

(defn click-to-copy [obj dom]
  (so/create ::click-to-copy {:value obj
                              :dom (dom/build dom)}))


(defmulti obj->dom* type :default ::default)

(defn ^String obj->dom [obj]
  (dom/build [:div.dt-dump (obj->dom* obj)]))

(defmethod obj->dom* ::default [obj]
  (js/console.log "unknown type" (type obj) obj)
  (dom/build [:div.dt-unknown (pr-str obj)]))

(defmethod obj->dom* nil [obj]
  (dom/build [:div.dt-nil "nil"]))

(defmethod obj->dom* js/String [obj]
  (dom/build
   (if (>= (count obj) 100)
     [:div.dt-string.dt-long-string [:textarea obj]]
     [:span.dt-string obj])))

(defmethod obj->dom* js/Number [obj]
  (dom/build
   [:span.dt-number (str obj)]))

(defmethod obj->dom* cljs.core.Keyword [obj]
  (dom/build
   [:span.dt-keyword (str obj)]))

(defmethod obj->dom* cljs.core.PersistentVector [obj]
  (let [n (count obj)]
    (dom/build
     [:table.dt-indexed
      (click-to-copy obj [:caption.edn-typehint (str "Vector [count: " n "]")])
      [:tbody
       (for [i (range n)
             :let [v (get obj i)]]
         [:tr
          [:td.dt-indexed-index i]
          [:td (obj->dom* v)]]
         )]])))

(defn map->html [obj]
  (let [n (count obj)
        mkeys (keys obj)
        mkeys (try
                (sort mkeys)
                (catch :default e
                  mkeys))]
    (dom/build
     [:table.dt-map
      [:caption (str "Map [count: " n "]")]
      [:tbody
       (for [k mkeys
             :let [v (get obj k)]]
         [:tr
          [:td.dt-mkey (obj->dom* k)]
          [:td.dt-mvalue (obj->dom* v)]]
         )]])))

(defmethod obj->dom* cljs.core.PersistentArrayMap [obj]
  (map->html obj))

(comment
  (defmethod obj->dom* com.cognitect.transit.types.TaggedValue [obj]
    (dom/build
      [:div.dt-tagged-value
       [:div.dt-tag (str "#" (.-tag obj))]
       [:div.dt-value (obj->dom* (.-rep obj))]])))
