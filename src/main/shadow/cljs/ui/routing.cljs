(ns shadow.cljs.ui.routing
  (:require
    [clojure.string :as str]
    [fulcro.client.primitives :as fp]
    [shadow.cljs.ui.transactions :as tx]))

(defn navigate-to-token! [r token]
  (js/console.log "NAVIGATE" token)

  (let [[main & more :as tokens] (str/split token #"/")]
    (case main
      "dashboard"
      (fp/transact! r [(tx/set-page {:page [:PAGE/dashboard 1]})])

      "repl"
      (fp/transact! r [(tx/set-page {:page [:PAGE/repl 1]})])

      "builds"
      (let [[build-id] more]
        (fp/transact! r [(tx/select-build {:build-id (keyword build-id)})])
        ))))

(defn setup-history [reconciler ^goog history]
  (let [start-token "dashboard"
        first-token (.getToken history)]
    (when (and (= "" first-token) (seq start-token))
      (.replaceToken history start-token)))

  (.listen history js/goog.history.EventType.NAVIGATE
    (fn [^goog e]
      (navigate-to-token! reconciler (.-token e))))

  (js/document.body.addEventListener "click"
    (fn [^js e]
      (when (and (zero? (.-button e))
                 (not (or (.-shiftKey e) (.-metaKey e) (.-ctrlKey e) (.-altKey e))))
        (when-let [a (some-> e .-target (.closest "a"))]

          (let [href (.getAttribute a "href")
                a-target (.getAttribute a "target")]

            (when (and href (seq href) (str/starts-with? href "/") (nil? a-target))
              (.preventDefault e)
              (.setToken history (subs href 1))))))))

  (.setEnabled history true))


