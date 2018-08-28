(ns shadow.cljs.ui.app
  (:require
    [cljs.pprint :refer (pprint)]
    [fulcro.client :as fc]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [fulcro.client.data-fetch :as fdf]
    [fulcro.client.network :as fnet]
    [fulcro.client.mutations :as fmut]
    [fulcro.client.routing :as froute :refer (defrouter)]
    ["react-dom" :as rdom]
    ["react" :as react]
    [shadow.cljs.model :as m]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.fulcro-mods :as fm]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.websocket :as ws]
    [cognitect.transit :as transit]
    [cljs.reader :as reader]
    [cljs.core.async :as async :refer (go)]
    [clojure.string :as str])
  (:import [goog.history Html5History]))

(defn dump [obj]
  (html/pre
    (with-out-str
      (pprint obj))))

(defsc BuildOverview [this {::m/keys [build-id build-state build-config-raw worker-active] :as props}]
  {:ident
   [::m/build-by-id ::m/build-id]

   :query
   [::m/build-config-raw
    ::m/build-id
    ::m/build-state
    ::m/worker-active]}

  (if-not build-id
    (html/div "Loading ...")
    (s/build-item {}
      (s/build-title (name build-id))

      (s/build-section "Actions")
      (s/build-toolbar
        (if worker-active
          (s/build-actions
            (s/build-action {:onClick #(fp/transact! this [(tx/build-watch-compile {:build-id build-id})])} "force-compile")
            (s/build-action {:onClick #(fp/transact! this [(tx/build-watch-stop {:build-id build-id})])} "stop watch"))

          (s/build-actions
            (s/build-action {:onClick #(fp/transact! this [(tx/build-watch-start {:build-id build-id})])} "start watch")
            (s/build-action {:onClick #(fp/transact! this [(tx/build-compile {:build-id build-id})])} "compile")
            (s/build-action {:onClick #(fp/transact! this [(tx/build-release {:build-id build-id})])} "release")
            )))

      (s/build-section "Status")
      (dump build-state)

      (s/build-section "Config")
      (s/build-config
        (dump build-config-raw)
        ))))

(def ui-build-overview (fp/factory BuildOverview {:keyfn ::m/build-id}))

(defsc MainNavBuild [this {::m/keys [build-id worker-active] :as props}]
  {:ident
   (fn []
     (js/console.log ::main-nav-build-ident props)
     [::m/build-by-id build-id])
   :query
   (fn []
     [::m/build-id
      ::m/worker-active
      [::active-build '_]
      ])}

  (when build-id
    (s/nav-sub-item
      {:key build-id
       :classes {:selected (= (::active-build props) build-id)}}

      (html/input
        {:type "checkbox"
         :checked worker-active
         :onChange
         (fn [e]
           (fp/transact! this [(if (.. e -target -checked)
                                 (tx/build-watch-start {:build-id build-id})
                                 (tx/build-watch-stop {:build-id build-id}))]))})

      (html/a {:href (str "/builds/" (name build-id))} (name build-id)))))

(def ui-main-nav-build (fp/factory MainNavBuild {:keyfn ::m/build-id}))

(defsc MainNav [this {::keys [build-list] :as props}]
  {:query
   (fn []
     [{[::build-list '_]
       (fp/get-query MainNavBuild)}])

   :initial-state
   (fn [props]
     {::build-list []})}

  (js/console.log ::main-nav-render)
  (s/nav-items
    (s/nav-item
      (html/a {:href "/dashboard"} "Dashboard"))

    (s/nav-item "Builds")
    (html/for [build build-list]
      (ui-main-nav-build build))))

(def ui-main-nav (fp/factory MainNav {}))

(defsc BuildPage [this {:keys [router/id router/page] :as props}]
  {:ident (fn [] [page id])

   :query
   [:router/id
    :router/page
    {::build (fp/get-query BuildOverview)}]

   :initial-state
   (fn [props]
     {:router/id 1
      :router/page :PAGE/build-page})}

  (when-let [x (::build props)]
    (ui-build-overview x)))

(defsc Dashboard [this {:keys [router/id router/page] :as props}]
  {:ident (fn [] [page id])

   :query
   [:router/id
    :router/page]

   :initial-state
   {:router/id 1
    :router/page :PAGE/dashboard}}

  (html/div "dashboard"))

(defrouter RootRouter ::router
  ; OR (fn [t p] [(:router/page p) (:db/id p)])
  [:router/page :router/id]
  :PAGE/dashboard Dashboard
  :PAGE/build-page BuildPage)

(def ui-root-router (fp/factory RootRouter))

(defsc Root [this {::keys [main-nav router] :as props}]
  {:initial-state
   (fn [p] {::router (fp/get-initial-state RootRouter {})
            ::main-nav (fp/get-initial-state MainNav {})
            ::env/ws-connected false})

   :query
   [::env/ws-connected
    {::router (fp/get-query RootRouter)}
    {::main-nav (fp/get-query MainNav)}
    ]}

  (s/page-container
    (s/main-nav
      (s/main-nav-header
        (s/main-nav-title "shadow-cljs"))
      (ui-main-nav main-nav))

    (s/main-contents
      (s/main-header
        (s/page-icons
          (html/div (if (::env/ws-connected props) "✔" "WS DISCONNECTED!"))))
      (ui-root-router router))))

(fm/add-mutation tx/select-build
  (fn [{::keys [active-build subscriptions] :as state} {:keys [ref] :as env} {:keys [build-id] :as params}]
    (let [screen-ident [:PAGE/build-page build-id]]

      (when-not (contains? subscriptions build-id)
        (ws/send env {::m/op ::m/subscribe
                      ::m/topic [::m/worker-output build-id]}))

      (-> state
          (update ::subscriptions conj build-id)
          (assoc ::active-build build-id)
          ;; FIXME: this doesn't feel right
          (update-in screen-ident merge {::build [::m/build-by-id build-id]
                                         :router/id build-id
                                         :router/page :PAGE/build-page})
          (froute/set-route* ::router screen-ident)))))

(fm/add-mutation tx/ws-open
  (fn [state env params]
    (assoc state ::env/ws-connected true)))

(fm/add-mutation tx/ws-close
  (fn [state env params]
    (assoc state ::env/ws-connected false)))

(fm/add-mutation tx/build-watch-start
  {:remote-returning BuildOverview})

(fm/add-mutation tx/build-watch-stop
  {:remote-returning BuildOverview})

(fm/add-mutation tx/build-watch-compile
  {:remote-returning BuildOverview})

(fm/add-mutation tx/build-compile
  {:remote-returning BuildOverview})

(fm/add-mutation tx/build-release
  {:remote-returning BuildOverview})

(fm/add-mutation tx/process-supervisor
  (fn [state env {:keys [op build-id] :as params}]
    (case op
      :worker-start
      (assoc-in state [::m/build-by-id build-id ::m/worker-active] true)
      :worker-stop
      (assoc-in state [::m/build-by-id build-id ::m/worker-active] false)
      )))

(fm/add-mutation tx/process-worker-output
  (fn [state env {:keys [build-id type] :as params}]
    ;; (js/console.log "worker-output" params)
    (assoc-in state [::m/build-by-id build-id ::m/build-state :status] type)
    ))

(defn start
  {:dev/after-load true}
  []
  (reset! env/app-ref (fc/mount @env/app-ref Root "root")))

(defn stop [])

(defn get-state []
  @(get-in @env/app-ref [:reconciler :config :state]))

(defn transit-read [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn transit-str [msg]
  (let [w (transit/writer :json)]
    (transit/write w msg)))

(defn navigate-to-token! [r token]
  (js/console.log "NAVIGATE" token)

  (let [[main & more :as tokens] (str/split token #"/")]
    (case main
      "dashboard"
      (fp/transact! r `[(froute/set-route
                          {:router ::router
                           :target [:PAGE/dashboard 1]})])

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

(defn ^:export init [data]
  (let [{:keys [mutations] :as data} (reader/read-string data)]

    (fm/init-remote-tx! mutations)

    ;; ws-in is easily overloaded due to spammy server
    ;; may need to reduce the events server side
    (let [ws-in
          (-> (async/sliding-buffer 10)
              (async/chan (map transit-read)))

          ws-out
          (async/chan 10 (map transit-str))

          history
          (doto (Html5History.)
            (.setPathPrefix "/")
            (.setUseFragment false))

          app
          (fc/new-fulcro-client
            :networking
            (fnet/make-fulcro-network "/api/graph"
              :global-error-callback
              (fn [& args] (js/console.warn "GLOBAL ERROR" args)))

            :started-callback
            (fn [{:keys [reconciler] :as app}]
              (ws/open reconciler ws-in ws-out)
              (setup-history reconciler history)
              (async/put! ws-out {::m/op ::m/subscribe
                                  ::m/topic ::m/supervisor})
              (fdf/load app ::m/build-configs BuildOverview {:target [::build-list]}))

            :initial-state
            (-> (fp/get-initial-state Root {})
                (assoc ::subscriptions #{}))

            :reconciler-options
            {:shared
             {::env/ws-in ws-in
              ::env/ws-out ws-out
              ::env/history history}

             ;; the defaults access js/ReactDOM global which we don't have/want
             :root-render
             #(rdom/render %1 %2)

             :root-unmount
             #(rdom/unmountComponentAtNode %)})]

      ;; FIXME: figure out how much I can do before first mound
      ;; reconciler doesn't seem to exist yet so can't transact?
      (reset! env/app-ref app)


      (start))))