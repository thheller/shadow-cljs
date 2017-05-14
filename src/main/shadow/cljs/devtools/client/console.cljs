(ns shadow.cljs.devtools.client.console
  (:require [clojure.string :as str]))

(defn- push-all [arr item]
  (if (vector? item)
    (doseq [it item]
      (.push arr it))
    (.push arr item)
    ))

(defn object-ref [obj]
  (when obj
    #js ["object" #js {:object obj}]))

(defn map->style [m]
  #js {:style
       (->> m
            (map (fn [[k v]] (str (name k) ": " v ";")))
            (str/join "")
            )})

(defn clj->jsonml
  [struct]

  (cond
    (nil? struct)
    nil

    (array? struct)
    struct

    (vector? struct)
    (let [[tag attrs & children] struct
          js #js [(name tag) (map->style attrs)]]
      (doseq [child children]
        (push-all js (clj->jsonml child)))
      js)

    (string? struct)
    struct

    (number? struct)
    struct

    (seq? struct)
    (into [] (map clj->jsonml) struct)

    :else
    (object-ref struct)
    ))

(deftype SeqFormatter []
  Object
  (shadow$formatter [this] true)
  (header [this obj]
    (when (or (sequential? obj) (set? obj))
      (clj->jsonml [:span {} (str (pr-str (type obj)) " [count: " (count obj) "]")])
      ))
  (hasBody [this obj]
    (boolean (seq obj)))
  (body [this s]
    (clj->jsonml [:ol {:margin 0}
                  (for [value s]
                    [:li {} (object-ref value)])])))

(deftype MapFormatter []
  Object
  (shadow$formatter [this] true)
  (header [this obj]
    (when (or (instance? cljs.core/PersistentHashMap obj)
              (instance? cljs.core/PersistentArrayMap obj)
              (record? obj))
      (clj->jsonml [:span {} (str (pr-str (type obj)) " [count: " (count obj) "]")])
      ))

  (hasBody [this obj]
    (boolean (seq obj)))

  (body [this m]
    (clj->jsonml
      [:table {:width "100%" :margin-left "14px"}
       (for [key (let [k (keys m)]
                   (try
                     (sort k)
                     (catch :default e
                       k)))
             :let [value (get m key)]]
         [:tr {:vertical-align "top"}
          [:td {} (object-ref key)]
          [:td {} (object-ref value)]])])))

(def keyword-style {:color "rgb(136, 19, 145)"})

(deftype KeywordFormatter []
  Object
  (shadow$formatter [this] true)
  (header [this obj]
    (when (keyword? obj)
      (clj->jsonml [:span keyword-style (pr-str obj)])
      ))
  (hasBody [this obj]
    false)
  (body [this m]
    nil))

(deftype SymbolFormatter []
  Object
  (shadow$formatter [this] true)
  (header [this obj]
    (when (symbol? obj)
      (clj->jsonml [:span keyword-style (pr-str obj)])
      ))
  (hasBody [this obj]
    false)
  (body [this m]
    nil))

(deftype DerefFormatter []
  Object
  (shadow$formatter [this] true)
  (header [this obj]
    (when (or (instance? Atom obj)
              (instance? Volatile obj))
      (clj->jsonml [:span keyword-style (str "@DEREF " (pr-str (type obj)))])
      ))
  (hasBody [this obj]
    true)
  (body [this v]
    (clj->jsonml [:div {:margin-left "14px"} (object-ref @v)])))

(defn install-all! []
  (when-let [f js/goog.global.devtoolsFormatters]
    (doto f
      (.push (KeywordFormatter.))
      (.push (MapFormatter.))
      (.push (SeqFormatter.))
      (.push (SymbolFormatter.))
      (.push (DerefFormatter.)))

    #_(js/console.log [1 "2" :3 'test {"hello" :world} '()])
    ))

(defn remove-all! []
  (let [all
        (->> (or js/goog.global.devtoolsFormatters #js [])
             (array-seq)
             (remove #(js/goog.object.get % "shadow$formatter"))
             (into-array))]
    (js/goog.object.set js/goog.global "devtoolsFormatters" all)))

;; in case this is live-reloaded, clean up first
;; has the side effect of creating window.devtoolsFormatters
;; do not want to look at the user agent as settings this
;; doesn't hurt any browser, only chrome with 47+ will use it
(remove-all!)
(install-all!)