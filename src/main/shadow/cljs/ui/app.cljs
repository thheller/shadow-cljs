(ns shadow.cljs.ui.app
  (:require
    ["react-dom" :as rdom]
    ["react" :as react]
    [cljs.core.async :as async :refer (go)]
    [com.fulcrologic.fulcro.application :as fa]
    [com.fulcrologic.fulcro.components :as fc :refer (defsc)]
    [com.fulcrologic.fulcro.data-fetch :as fdf]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.markup.react :as html :refer ($ defstyled)]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.fulcro-mods :as fm]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.websocket :as ws]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.ui.routing :as routing]
    [shadow.cljs.ui.pages.loading :as page-loading]
    [shadow.cljs.ui.pages.dashboard :as page-dashboard]
    [cljs-test-display.favicon :as favicon]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.networking.http-remote :as fhr]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ident-optimized])
  (:import [goog.history Html5History]))

(defsc MainNavBuild [this props {:keys [selected]}]
  {:ident
   (fn []
     [::m/build-id (::m/build-id props)])
   :query
   (fn []
     [::m/build-id
      ::m/build-status
      ::m/build-worker-active])}

  (let [{::m/keys [build-id build-worker-active]} props]
    (when build-id
      (s/nav-build-item {:key build-id}
        (s/nav-build-checkbox
          (html/input
            {:type "checkbox"
             :checked build-worker-active
             :onChange
             (fn [e]
               (fc/transact! this [(if (.. e -target -checked)
                                     (tx/build-watch-start {:build-id build-id})
                                     (tx/build-watch-stop {:build-id build-id}))]))}))

        (s/nav-build-link {:href (str "/build/" (name build-id))} (name build-id))))))


(def ui-main-nav-build (fc/factory MainNavBuild {:keyfn ::m/build-id}))

(defsc MainNav [this props]
  {:query
   (fn []
     [{[::ui-model/build-list '_]
       (fc/get-query MainNavBuild)}
      [::ui-model/notifications '_]
      [::ui-model/ws-connected '_]])

   :ident
   (fn []
     [::ui-model/globals ::ui-model/nav])

   :initial-state
   (fn [props]
     {::ui-model/build-list []})}

  (let [{::ui-model/keys [build-list]} props]
    (s/main-nav
      (s/main-nav-title
        (s/nav-link {:href "/dashboard"} "Dashboard"))

      (s/nav-item
        (s/nav-item-title
          (s/nav-link {:href "/builds"} "Builds"))
        (s/nav-sub-items
          (html/for [{::m/keys [build-id] :as build} build-list]
            (ui-main-nav-build build))))

      (s/nav-item
        (s/nav-item-title
          (s/nav-link {:href "/repl"} "REPL")))

      (s/nav-item
        (s/nav-item-title
          (s/nav-link {:href "/inspect"} "Inspect")))

      (s/nav-fill {})

      (s/nav-item
        (s/nav-item-title
          (html/label
            (html/input {:type "checkbox"
                         :checked (::ui-model/notifications props)
                         :onChange (fn [e]
                                     (let [wanted (-> e .-target .-checked)
                                           tx [(tx/toggle-notifications {:wanted wanted})]]

                                       (cond
                                         (not wanted)
                                         (fc/transact! this tx)

                                         (and wanted (= js/Notification.permission "granted"))
                                         (fc/transact! this tx)

                                         :no-permission
                                         (-> (js/Notification.requestPermission)
                                             (.then (fn [perm]
                                                      (when (= "granted" perm)
                                                        (fc/transact! this tx))))))))})
            "Notifications")))
      #_(s/nav-item
          (html/div (if (::ui-model/ws-connected props) "✔" "WS DISCONNECTED!"))))))

(def ui-main-nav (fc/factory MainNav {}))

(routing/register ::ui-model/root-router ::ui-model/page-dashboard
  {:class page-dashboard/Page
   :factory page-dashboard/ui-page})

(routing/register ::ui-model/root-router ::ui-model/page-loading
  {:class page-loading/Page
   :factory page-loading/ui-page
   :default true})

(defsc Root [this {::ui-model/keys [ws-connected main-nav builds-loaded] :as props}]
  {:initial-state
   (fn [p]
     {::ui-model/root-router {}
      ::ui-model/main-nav (fc/get-initial-state MainNav {})
      ::ui-model/builds-loaded false})

   :query
   [::ui-model/builds-loaded
    ::ui-model/ws-connected
    {::ui-model/root-router (routing/get-query ::ui-model/root-router)}
    {::ui-model/main-nav (fc/get-query MainNav)}]}

  (cond
    (not builds-loaded)
    (html/div "Loading ...")

    (not ws-connected)
    (html/div "WebSocket not connected ...")

    :else
    (s/page-container
      (ui-main-nav main-nav)
      (routing/render ::ui-model/root-router this props))))

(fm/handle-mutation tx/select-build
  (fn [state {:keys [ref] :as env} {:keys [build-id] :as params}]
    (-> state
        (assoc-in [::ui-model/globals ::ui-model/nav ::ui-model/active-build] build-id))))

(fm/handle-mutation tx/ws-open
  (fn [state env params]
    (assoc state ::ui-model/ws-connected true)))

(fm/handle-mutation tx/toggle-notifications
  (fn [state env {:keys [wanted] :as params}]
    (assoc state ::ui-model/notifications wanted)
    ))

(fm/handle-mutation tx/ws-close
  (fn [state env params]
    (assoc state ::ui-model/ws-connected false)))

(defn update-worker-active [state env {:keys [op build-id] :as params}]
  (case op
    :worker-start
    (assoc-in state [::m/build-id build-id ::m/build-worker-active] true)
    :worker-stop
    (assoc-in state [::m/build-id build-id ::m/build-worker-active] false)
    ))

(defn update-dashboard [state env params]
  (let [active-builds
        (->> (::m/build-id state)
             (vals)
             (filter ::m/build-worker-active)
             (sort-by ::m/build-id)
             (map #(vector ::m/build-id (::m/build-id %)))
             (into []))]
    (assoc-in state [::ui-model/page-dashboard 1 ::ui-model/active-builds] active-builds)))

(fm/handle-mutation tx/builds-loaded
  [(fn [state env params]
     (assoc state ::ui-model/builds-loaded true))
   update-dashboard])

(fm/handle-mutation tx/process-supervisor
  [update-worker-active
   update-dashboard])

(def icon-red (favicon/color-data-url "#d00" 16))
(def icon-green (favicon/color-data-url "#0d0" 16))
(def icon-yellow (favicon/color-data-url "#FFFF00" 16))

(defn notification-summary [state]
  (->> state
       (sort-by first)
       (map (fn [[build-id status]]
              (str "[" build-id "]: " (case status
                                        :failed
                                        "FAILED!"
                                        0
                                        "Success."
                                        1
                                        "1 Warning."
                                        (str status " Warnings.")))
              ))
       (str/join "\n")))

(defn update-notification-state [state build-id status]
  (let [before (::m/notification-state state)
        after (assoc before build-id status)]
    (if (= before after)
      state
      (do (js/console.log "state changed, fire notification" (pr-str after) (::ui-model/notifications state))
          (let [icon
                (cond
                  (->> after (vals) (some #(= :failed %)))
                  icon-red
                  (->> after (vals) (some pos?))
                  icon-yellow
                  :else
                  icon-green)]

            (when (::ui-model/notifications state)
              (let [n (js/Notification. "shadow-cljs"
                        #js {:silent true
                             :tag "shadow-cljs-build-status-notification"
                             :renotify true
                             :icon icon
                             :body (notification-summary after)})]
                (.addEventListener n "click"
                  (fn [e]
                    (js/goog.global.window.focus)))
                )))

          (assoc state ::m/notification-state after)))))


(defn count-warnings [{:keys [sources] :as info}]
  (reduce
    (fn [c {:keys [warnings]}]
      (+ c (count warnings)))
    0
    sources))

(fm/handle-mutation tx/process-worker-broadcast
  (fn [state env {:keys [build-id type info] :as params}]
    (case type
      :build-complete
      (-> state
          (update-notification-state build-id (count-warnings info))
          (update-in [::m/build-id build-id] merge {::m/build-info info
                                                    ;; FIXME: should actually update instead of just removing
                                                    ;; but no access to the code from here
                                                    ::m/build-ns-summary nil
                                                    ::m/build-provides
                                                    (->> (:sources info)
                                                         (mapcat :provides)
                                                         (sort)
                                                         (into))}))

      :build-failure
      (update-notification-state state build-id :failed)

      ;; ignore
      state)))


(fm/handle-mutation tx/process-build-status-update
  (fn [state env {:keys [build-id build-status] :as params}]
    (assoc-in state [::m/build-id build-id ::m/build-status] build-status)
    ))

(defn update-runtimes [{::m/keys [runtime-id] :as state}]
  (let [all
        (->> (get state ::m/runtime-id)
             (vals)
             (filter ::m/runtime-active)
             (sort-by #(get-in % [::m/runtime-info :since]))
             (map (fn [{::m/keys [runtime-id]}]
                    [::m/runtime-id runtime-id]))
             (into []))]

    (assoc-in state [::ui-model/page-repl 1 ::ui-model/runtimes] all)
    ))

(defn broadcast [env topic msg]
  (let [msg
        (assoc msg ::ui-model/topic topic)

        ch
        (get-in env [:shared ::env/broadcast-chan])]

    (js/console.log ::broadcast msg)
    (when-not (async/offer! ch msg)
      (js/console.warn "broadcast overloaded" msg)
      )))

(fm/handle-mutation tx/process-tool-msg
  [(fn [state env {::m/keys [op runtime-id] :as msg}]
     (case op
       ::m/runtime-connect
       (update-in state [::m/runtime-id runtime-id] merge (-> msg
                                                              (assoc ::m/runtime-active true)
                                                              (dissoc msg ::m/op)))

       ::m/runtime-disconnect
       (update-in state [::m/runtime-id runtime-id] assoc ::m/runtime-active false)


       ::m/session-started
       (let [{::m/keys [session-id session-ns]} msg]
         (update-in state [::m/session-id session-id] merge {::m/session-status :started
                                                             ::m/session-ns session-ns}))

       ::m/session-result
       (let [{::m/keys [printed-result result-id session-id]} msg]
         (broadcast env [::ui-model/session-out session-id] {::m/text printed-result})
         (-> state
             (assoc-in [::m/result-id result-id]
               {::m/result-id result-id
                ::m/session [::m/session-id session-id]
                ::m/printed-result printed-result})
             (update-in [::m/session-id session-id ::m/results] util/conj-vec [::m/result-id result-id])))

       ::m/session-update
       (let [{::m/keys [session-id session-ns]} msg]
         (broadcast env [::ui-model/session-out session-id] {::m/text (str "Switched namespace to: " session-ns)})
         (-> state
             (assoc-in [::m/session-id session-id ::m/session-ns] session-ns)
             ))

       ::m/session-out
       (let [{::m/keys [session-id session-out]} msg]
         (broadcast env [::ui-model/session-out session-id] {::m/text session-out})
         state)

       ::m/session-err
       (let [{::m/keys [session-id session-err]} msg]
         (broadcast env [::ui-model/session-out session-id] {::m/text session-err})
         state)

       ;; unhandled
       (do (js/console.log ::unknown-tool-msg msg)
           state)))
   update-runtimes])

(fm/handle-mutation tx/build-watch-start
  {:remote-returning MainNavBuild})

(fm/handle-mutation tx/build-watch-stop
  {:remote-returning MainNavBuild})

(fm/handle-mutation tx/build-watch-compile
  {:remote-returning MainNavBuild})

(fm/handle-mutation tx/build-compile
  {:remote-returning MainNavBuild})

(fm/handle-mutation tx/build-release
  {:remote-returning MainNavBuild})

(defn start
  {:dev/after-load true}
  []
  (fa/mount! env/app Root "root")
  ;; (reset! env/app-ref (fc/mount @env/app-ref Root "root"))
  )

(defn stop [])

(defn init []
  (let [ws-in
        (-> (async/sliding-buffer 10)
            (async/chan (map util/transit-read)))

        ws-out
        (async/chan 10 (map util/transit-str))

        ws-url
        (str "ws"
             (if (= "https:" js/document.location.protocol) "s" "")
             "://"
             js/document.location.host
             "/api/ws")

        graph-url
        "/api/graph"

        broadcast-chan
        (async/chan 10)

        broadcast-pub
        (async/pub broadcast-chan ::ui-model/topic)

        history
        (doto (Html5History.)
          (.setPathPrefix "/")
          (.setUseFragment false))]

    (set! env/app (fa/fulcro-app
                    {:remotes
                     {:remote (fhr/fulcro-http-remote {:url "/api/graph"})}

                     :initial-db
                     (-> (fc/get-initial-state Root {})
                         (assoc ::ui-model/notifications (= js/Notification.permission "granted"))
                         (assoc-in [::ui-model/page-repl 1] {})
                         (assoc-in [::ui-model/page-builds 1] {})
                         (assoc-in [::ui-model/page-loading 1] {})
                         (assoc-in [::ui-model/page-inspect 1] {})
                         (assoc-in [::ui-model/page-dashboard 1] {}))

                     :render-root!
                     #(rdom/render %1 %2)

                     :unmount-root!
                     #(rdom/unmountComponentAtNode %)

                     :shared
                     {::env/ws-in ws-in
                      ::env/ws-out ws-out
                      ::env/history history
                      ::env/broadcast-chan broadcast-chan
                      ::env/broadcast-pub broadcast-pub}}))

    (ws/open ws-url ws-in ws-out)

    (routing/setup-history history)

    (async/put! ws-out
      {::m/op ::m/subscribe
       ::m/topic ::m/supervisor})

    (async/put! ws-out
      {::m/op ::m/subscribe
       ::m/topic ::m/worker-broadcast})

    (async/put! ws-out
      {::m/op ::m/subscribe
       ::m/topic ::m/build-status-update})

    (fdf/load env/app ::m/http-servers page-dashboard/HttpServer
      {:target [::ui-model/page-dashboard 1 ::ui-model/http-servers]})

    (fdf/load env/app ::m/build-configs MainNavBuild
      {:target [::ui-model/build-list]
       :without #{::m/build-info}
       :post-mutation `tx/builds-loaded
       :refresh [::ui-model/builds-loaded]})

    (start)))