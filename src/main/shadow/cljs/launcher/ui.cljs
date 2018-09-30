(ns shadow.cljs.launcher.ui
  (:require
    ["react" :as react]
    ["react-dom" :as rdom]
    ["electron" :as e :refer (ipcRenderer)]
    [fulcro.client :as fc]
    [fulcro.client.network :as fnet]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.ui.fulcro-mods :as fm :refer (deftx)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.launcher.style :as s]))

(defonce app-ref (atom nil))

(deftx tx-toggle-menu
  {})

(deftx tx-show-project-info
  {})

(deftx tx-show-reload-project-ui
  {})

(deftx tx-select-project
  {})

(deftx tx-set-project-status
  {::m/project-id ident?
   ::m/project-status some?})

(defn ipc-send
  ([op]
   (ipc-send op {}))
  ([op data]
   (let [data (assoc data ::m/op op)
         msg (util/transit-str data)]
     (.send ipcRenderer "msg" msg))))


(defsc ProjectInfo [this {::m/keys [project-id] :as props}]
  {:ident
   (fn []
     [::m/project-id project-id])

   :query
   (fn []
     [::m/project-id
      ::m/project-name
      ::m/project-location
      ::m/project-status])}

  (let [{::m/keys [project-name project-status project-server-url]} props]
    (s/project-info-container
      (s/project-info-item
        (s/project-info-label "Project Root")
        (let [full-path
              "C:\\Users\\thheller\\code\\shadow-cljs"]

          (s/project-info-value
            (html/a {:href "#"
                     :onClick
                     (fn [^js e]
                       (.preventDefault e)
                       (ipc-send ::m/open-item {:path full-path}))}
              full-path
              ))))

      (s/project-info-item
        (s/project-info-label "Server Status: Not running")
        (s/project-info-value
          (html/div
            (html/button "Start"))))

      (s/project-info-item
        (s/project-info-label "Server Status: Running separately")
        (s/project-info-value
          (html/div
            (html/button "Show UI")
            (html/span "Server was started elsewhere ..."))))

      (s/project-info-item
        (s/project-info-label "Server Status: Waiting ...")
        (s/project-info-value
          (html/div
            (html/button "Kill"))))

      (s/project-info-item
        (s/project-info-label "Server Status: Running PID #1234")
        (s/project-info-value
          (html/div
            (html/button "Show UI")
            (html/button "Shutdown")
            (html/button "Kill")))))))

(def ui-project-info (fp/factory ProjectInfo {:keyfn ::m/project-id}))

(defsc ActiveProject [this {::m/keys [project-id] :as props}]
  {:ident
   (fn []
     [::m/project-id project-id])

   :query
   (fn []
     [::m/project-id
      ::m/project-name
      ::m/project-location
      ::m/project-status])}

  (let [{::m/keys [project-name project-status project-server-url]} props]
    (s/project-container
      (s/project-toolbar
        (s/project-actions
          (s/project-action
            {:onClick
             (fn [e]
               (.preventDefault e)
               (fp/transact! this [(tx-toggle-menu)]))}
            "<")
          (s/project-action {:onClick #(fp/transact! this [(tx-show-project-info)])} "I"))
        (s/project-title project-name)
        (s/project-actions
          (s/project-action {:onClick #(fp/transact! this [(tx-show-reload-project-ui)])} "R")))

      (ui-project-info props)

      #_(s/project-iframe {:src "http://localhost:9630"})

      (s/project-console
        (s/project-info-label "Server Log"))
      )))

(def ui-active-project (fp/factory ActiveProject {:keyfn ::m/project-id}))

(defsc ProjectListItem [this {::m/keys [project-id] :as props} {:keys [on-select selected]}]
  {:ident
   (fn []
     [::m/project-id project-id])

   :query
   (fn []
     [::m/project-id
      ::m/project-name
      ::m/project-short-path
      ::m/project-location
      ::m/project-status])}

  (let [{::m/keys [project-name project-short-path]} props]
    (s/project-listing-item {:classes {:selected selected}
                             :onClick
                             (fn [e]
                               (.preventDefault e)
                               (on-select project-id))}
      (html/b project-name)
      (html/div project-short-path)
      )))

(def ui-project-list-item (fp/factory ProjectListItem {:keyfn ::m/project-id}))


(defstyled button :button [env]
  {:flex 1
   :margin [0 10]})

(defn ui-sidebar [container projects active-project show-sidebar]
  (s/main-sidebar {:classes {:expanded show-sidebar}}
    (s/project-listing
      (s/project-listing-title "Projects")
      (s/project-listing-items
        (html/for [proj projects]
          (-> proj
              (fp/computed
                {:selected (= (::m/project-id active-project)
                              (::m/project-id proj))
                 :on-select
                 (fn [proj]
                   (fp/transact! container [(tx-select-project {:project-id proj})]))})
              (ui-project-list-item))))

      (s/project-listing-actions
        (button
          {:disabled true
           :title "Coming soon ... I hope ..."}
          "Create new Project")
        (button {:onClick #(ipc-send ::m/project-find {})} "+ Add Existing Project")
        ))))

(defsc Root [this {::keys [sidebar active-project projects] :as props}]
  {:query
   (fn []
     [::sidebar
      {::projects (fp/get-query ProjectListItem)}
      {::active-project (fp/get-query ProjectListItem)}])

   :initial-state
   (fn [p]
     {::sidebar false
      ::projects
      [{::m/project-id "C:/Users/thheller/code/shadow-cljs"
        ::m/project-name "shadow-cljs"
        ::m/project-short-path "~/code/shadow-cljs"
        ::m/project-server-url "http://localhost:9630"
        ::m/project-status :running}
       {::m/project-id "C:/Users/thheller/code/shadow"
        ::m/project-name "shadow"
        ::m/project-short-path "~/code/shadow"
        ::m/project-server-url "http://localhost:9630"
        ::m/project-status :running}
       {::m/project-id "C:/Users/thheller/code/gatsby-cljs"
        ::m/project-name "gatsby-cljs"
        ::m/project-short-path "~/code/gatsby-cljs"
        ::m/project-server-url "http://localhost:9630"
        ::m/project-status :running}
       {::m/project-id "C:/Users/thheller/code/next-cljs"
        ::m/project-name "next-cljs"
        ::m/project-short-path "~/code/next-cljs"
        ::m/project-server-url "http://localhost:9630"
        ::m/project-status :running}]})}

  (js/console.log "Root" props)

  (s/app-frame
    ;; FIXME: defsc sidebar
    (ui-sidebar this
      projects
      active-project
      (or sidebar (not (seq active-project))))

    (s/main-cols
      (if (seq active-project)
        (ui-active-project active-project)

        (s/splash-container
          (s/logo-header
            (s/logo-img {:src "img/shadow-cljs.png" :width 200})
            (s/logo-title "shadow-cljs")
            (html/p "No Project selected.")

            (html/a {:href "https://shadow-cljs.github.io/docs/UsersGuide.html" :target "_blank"} "User's Guide")
            ))))))


(defmulti handle-ipc (fn [r msg] (::m/op msg)))

(defmethod handle-ipc ::m/project-found [r params]
  )

(defmethod handle-ipc ::m/project-status [r params]
  (fp/transact! r [(tx-set-project-status params)]))

(fm/handle-mutation tx-set-project-status
  (fn [state env {::m/keys [project-id] :as params}]
    (update-in state [::m/project-id project-id] merge (dissoc params ::m/op))))

(fm/handle-mutation tx-select-project
  (fn [state {:keys [ref] :as env} {:keys [project-id] :as params}]
    (js/console.log "tx-select-project" ref project-id)
    (-> state
        (assoc ::sidebar false)
        (assoc-in (conj ref ::active-project) [::m/project-id project-id]))))

(fm/handle-mutation tx-toggle-menu
  {:refresh
   (fn [env params]
     [::sidebar])
   :state-action
   (fn [state env params]
     (js/console.log "tx-toggle-menu")
     (update state ::sidebar not))})

(defn ipc-listen-fn [event arg]
  (let [msg (util/transit-read arg)]
    (js/console.log ::ipc-renderer msg)
    (let [{:keys [reconciler]} @app-ref]
      (handle-ipc reconciler msg))))

(defn ipc-listen []
  (.on ipcRenderer "msg" ipc-listen-fn))

(defn start
  {:dev/after-load true}
  []
  (reset! app-ref (fc/mount @app-ref Root "root")))

(defn stop [])

(defn init []
  (let [app
        (fc/new-fulcro-client
          :started-callback
          (fn [{:keys [reconciler] :as app}]
            (js/console.log "project start")
            (ipc-listen)
            (ipc-send ::m/project-status
              {::m/project-id "C:\\Users\\thheller\\code\\shadow-cljs"
               ::m/project-path "C:\\Users\\thheller\\code\\shadow-cljs"}))

          :reconciler-options
          {:shared
           {}

           ;; the defaults access js/ReactDOM global which we don't have/want
           :root-render
           #(rdom/render %1 %2)

           :root-unmount
           #(rdom/unmountComponentAtNode %)})]

    (reset! app-ref app)
    (start)))

