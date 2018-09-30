(ns shadow.cljs.ui.routing
  (:require
    [clojure.string :as str]
    [fulcro.client.primitives :as fp]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.fulcro-mods :as fm :refer (deftx)]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.util :as util]
    [shadow.loader :as sl]))

(defonce routes-ref
  (atom {:loading #{}
         :active {}
         :routers {}}))

(defn check-active! [router-id]
  (when-not (get-in @routes-ref [:active router-id])
    (throw (ex-info "router not active" {}))))

(defn render [router-id component props]
  (check-active! router-id)
  (let [[route-key route-id]
        (get-in @routes-ref [:active router-id])

        {:keys [factory]}
        (get-in @routes-ref [:routers router-id :routes route-key])]

    (factory (get props router-id))))

(defn get-query [router-id]
  (check-active! router-id)
  (let [[route-key route-id]
        (get-in @routes-ref [:active router-id])

        {:keys [class] :as config}
        (get-in @routes-ref [:routers router-id :routes route-key])]

    (fp/get-query class)))

(defn get-ident [router-id props]
  (check-active! router-id)
  (let [route-id
        (get-in @routes-ref [:active router-id])]

    [router-id route-id]))

(defn register [router-id route-key {:keys [default] :as router-config}]
  (swap! routes-ref
    (fn [routes]
      (-> routes
          (update-in [:routers router-id :routes route-key] merge (assoc router-config :route-key route-key))
          (cond->
            default
            (assoc-in [:routers router-id :default] route-key)

            (and default (not (get-in routes [:active router-id])))
            (update :active assoc router-id [route-key 1])
            )))))

(defn select!
  [router-id ident]
  {:pre [(vector? ident)]}
  (swap! routes-ref update :active assoc router-id ident))

(deftx set-route
  {:router-id keyword?
   :route-key keyword?
   :route-id any?})

(defn set-route* [state router ident]
  (assoc state router ident))

(fm/handle-mutation set-route
  {:refresh
   (fn [env {:keys [router] :as params}]
     [router])
   :state-action
   (fn [state env {:keys [router ident] :as params}]
     ;; FIXME: figure out how to store this only in app-db
     (select! router ident)
     (set-route* state router ident))})

(defn load-module [r module-id]
  (when-not (sl/loaded? module-id)
    ;; FIXME: only do this after a timeout, load should be more or less instant always here
    (swap! routes-ref update :loading util/conj-set ::ui-model/root-router)
    (fp/transact! r [(set-route {:router ::ui-model/root-router
                                 :ident [::ui-model/page-loading 1]})]))

  (-> (sl/load module-id)
      (.then (fn []
               (swap! routes-ref update :loading disj ::ui-model/root-router)))))

(defn navigate-to-token! [{:keys [state] :as r} token]
  (js/console.log "NAVIGATE" token)

  (let [[main & more :as tokens] (str/split token #"/")]
    (case main
      "dashboard"
      (fp/transact! r [(set-route {:router ::ui-model/root-router
                                   :ident [::ui-model/page-dashboard 1]})])

      "repl"
      (-> (load-module r "repl")
          (.then #(js* "shadow.cljs.ui.pages.repl.route(~{}, ~{});" r more)))

      "builds"
      (-> (load-module r "build")
          (.then #(js* "shadow.cljs.ui.pages.build.route(~{}, ~{});" r more)))
      )))

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


(defn set-token! [env new-token]
  (let [^goog history (get-in env [:shared ::env/history])]
    (js/console.log "set-token!" env new-token)
    (.setToken history new-token)
    ))
