(ns shadow.cljs.launcher.ui
  (:require
    ["react" :as react]
    ["react-dom" :as rdom]
    ["electron" :as e :refer (ipcRenderer)]
    ["xterm" :as xterm]
    ["xterm/lib/addons/fit/fit" :as xterm-fit]
    [fulcro.client :as fc]
    [fulcro.client.network :as fnet]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.ui.fulcro-mods :as fm :refer (deftx)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.launcher.style :as s]
    [clojure.string :as str]))

(defonce app-ref (atom nil))

(defonce server-buffers-ref (atom {}))

(deftx tx-toggle-menu
  {})

(deftx tx-toggle-project-info
  {})

(deftx tx-show-reload-project-ui
  {})

(deftx tx-select-project
  {})

(deftx tx-forget-project
  {})

(deftx tx-proc-exit
  "a managed process exited"
  {:project-id project-id?
   :exit-code nat-int?})

(deftx tx-project-found
  "result of find existing project"
  {:path abs-path?})

(deftx tx-state-loaded
  "on-disk state has been loaded"
  {:file abs-path?
   :data state?})

(deftx tx-set-project-status
  {:project-id ident?
   :data project-data?})

(defn ipc-send
  ([op]
   (ipc-send op {}))
  ([op data]
   (let [data (assoc data ::m/op op)
         msg (util/transit-str data)]
     (js/console.log "ipc-send" op data)
     (.send ipcRenderer "msg" msg))))

(defn button-kill [this project-id]
  (html/button
    {:onClick
     (fn [e]
       (.preventDefault e)
       (ipc-send ::m/project-kill {:project-id project-id}))}
    "Kill"))

(defn button-show-ui [this project-id]
  (html/button
    {:onClick
     (fn [e]
       (.preventDefault e)
       (fp/transact! this [(tx-toggle-project-info {:project-id project-id
                                                    :force false})]))}
    "Show UI"))

(defn button-shutdown [this project-id]
  (html/button
    {:onClick
     (fn [e]
       (.preventDefault e)
       (ipc-send ::m/project-shutdown {:project-id project-id}))}
    "Shutdown"))



(defsc ProjectInfo [this {::m/keys [project-id] :as props}]
  {:ident
   (fn []
     [::m/project-id project-id])

   :query
   (fn []
     [::m/project-id
      ::m/project-name
      ::m/project-path
      ::m/project-short-path
      ::m/project-status
      ::m/project-pid
      ::m/project-server-url])}

  (let [{::m/keys [project-path project-status project-server-url]} props]

    (s/project-info-container
      (s/project-info-item
        (s/right-action-button
          {:onClick
           (fn [e]
             (.preventDefault e)
             (fp/transact! this [(tx-forget-project {:project-id project-id})]))}

          "Forget Project")
        (s/project-info-label "Project Root")
        (s/project-info-value
          (html/a {:href "#"
                   :onClick
                   (fn [^js e]
                     (.preventDefault e)
                     (ipc-send ::m/open-item {:path project-path}))}
            project-path)))

      (s/project-info-item
        (s/project-info-label "NPM")
        (s/project-info-value
          (html/button
            {:onClick
             (fn [e]
               (.preventDefault e)
               (ipc-send ::m/project-npm-install {:project-id project-id :project-path project-path}))}
            "Run 'npm install'")))

      (case project-status
        :running
        (s/project-info-item
          (s/project-info-label "Server Status: Running separately")
          (s/project-info-value
            (html/div
              (button-show-ui this project-id)
              (html/span "Server was started elsewhere ..."))))

        (:error :inactive)
        (s/project-info-item
          (s/project-info-label "Server Status: Not running")
          (s/project-info-value
            (html/div
              (html/button
                {:onClick
                 (fn [e]
                   (.preventDefault e)
                   (ipc-send ::m/project-start {:project-id project-id
                                                :project-path project-path}))}
                "Start"))))

        :starting
        (s/project-info-item
          (s/project-info-label (str "Server Status: Starting PID #" (::m/project-pid props)))
          (s/project-info-value
            (html/div
              (button-kill this project-id)
              )))

        :managed
        (s/project-info-item
          (s/project-info-label (str "Server Status: Running PID #" (::m/project-pid props)))
          (s/project-info-value
            (html/div
              (button-show-ui this project-id)
              (button-shutdown this project-id)
              (button-kill this project-id))))

        :pending
        (s/project-info-item
          (s/project-info-label "Server Status: Pending ..."))

        (s/project-info-item
          (s/project-info-label "Server Status: Unknown ...")
          (s/project-info-value (util/dump props)))
        ))))

(def ui-project-info (fp/factory ProjectInfo {:keyfn ::m/project-id}))

(defn attach-terminal [comp term-div project-id]
  ;; (js/console.log ::attach-terminal term-div comp)

  (if-not term-div
    (let [{::keys [^js term term-resize]} (util/get-local! comp)]
      (swap! server-buffers-ref update project-id dissoc :term)
      (when term-resize
        (js/window.removeEventListener "resize" term-resize))
      (when term
        (.destroy term))
      (util/swap-local! comp dissoc ::term ::term-resize))

    ;; fresh mount
    (let [term
          (doto (xterm/Terminal. #js {:convertEol true
                                      :fontFamily "monospace"
                                      :fontSize 14
                                      :theme #js {:foreground "#000000"
                                                  :background "#FFFFFF"
                                                  :selection "rgba(0,0,0,0.3)"}})
            (.open term-div))

          term-resize
          (fn []
            (xterm-fit/fit term))]

      (swap! server-buffers-ref assoc-in [project-id :term] term)

      (term-resize)

      (let [buffer (get-in @server-buffers-ref [project-id :buffer])]
        (when (seq buffer)
          (.write term buffer)))

      (js/window.addEventListener "resize" term-resize)

      (util/swap-local! comp assoc
        ::term term
        ::term-resize term-resize))))

(defsc ActiveProject [this {::m/keys [project-id] :as props}]
  {:ident
   (fn []
     [::m/project-id project-id])

   :query
   (fn []
     [::show-info
      ::show-console
      ::m/project-id
      ::m/project-name
      ::m/project-path
      ::m/project-short-path
      ::m/project-status
      ::m/project-pid
      ::m/project-server-url])}

  (let [{::m/keys [project-name project-short-path project-server-url project-status]} props

        show-iframe?
        (and (seq project-server-url)
             (not (true? (::show-info props))))

        show-console?
        (or (::show-console props)
            (not show-iframe?))]

    (s/project-container
      (s/project-toolbar
        (s/project-actions
          (s/project-action
            {:onClick
             (fn [e]
               (.preventDefault e)
               (fp/transact! this [(tx-toggle-menu)]))}
            "<")
          (s/project-action
            {:onClick
             (fn [e]
               (.preventDefault e)
               (fp/transact! this [(tx-toggle-project-info {:project-id project-id})]))} "I"))
        (s/project-title project-short-path)
        (s/project-actions
          (s/project-action {:onClick #(fp/transact! this [(tx-show-reload-project-ui)])} "R")))

      (if-not show-iframe?
        (ui-project-info props)
        (s/project-iframe {:src project-server-url}))

      (when show-console?
        (s/project-console-container
          (s/project-console-header "Project Output")
          (s/project-console
            {:ref (util/comp-fn this ::term-ref attach-terminal project-id)})
          )))))

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
      ::m/project-status
      ::m/project-server-url])}

  (let [{::m/keys [project-name project-short-path project-server-url]} props]
    (s/project-listing-item {:classes {:selected selected
                                       :active (seq project-server-url)}
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
      {::active-project (fp/get-query ActiveProject)}])

   :initial-state
   (fn [p]
     {::sidebar false
      ::projects []})}

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

;; FIXME: should really look into making ipc a fulcro remote
;; but adding pathom to the main process seems like overkill
;; just to process a few simple ops?

(defmulti handle-ipc (fn [r msg] (::m/op msg)))

(defmethod handle-ipc :default [r msg]
  (js/console.log "unhandled IPC" msg))

;; 10kb scrollback probably enough right?
(def MAX-BUFFER-SIZE (* 10 1024))

(defn append-maybe-truncate [buffer data]
  (let [new (str buffer data)
        size (.-length new)]
    (if (> size MAX-BUFFER-SIZE)
      (subs new (- size MAX-BUFFER-SIZE))
      new)))

(defn buffer-append [project-id data]
  (swap! server-buffers-ref update project-id
    (fn [{:keys [term] :as current}]
      (when term
        (.write term data))
      (update current :buffer append-maybe-truncate data))))

(defmethod handle-ipc ::m/proc-stdout [r {:keys [project-id data]}]
  (buffer-append project-id data))

(defmethod handle-ipc ::m/proc-stderr [r {:keys [project-id data]}]
  (buffer-append project-id data))

(defmethod handle-ipc ::m/proc-starting [r {:keys [project-id pid]}]
  (swap! server-buffers-ref assoc-in [project-id :buffer] "")
  (when-let [term (get-in @server-buffers-ref [project-id :term])]
    (.clear term))

  (fp/transact! r [(tx-set-project-status {:project-id project-id
                                           :data
                                           {::m/project-status :starting
                                            ::show-info nil
                                            ::m/project-pid pid}})]))

(defn ipc->tx [ipc-op tx]
  (-add-method handle-ipc ipc-op
    (fn [r params]
      (fp/transact! r [(tx params)]))))

(ipc->tx ::m/proc-exit tx-proc-exit)
(ipc->tx ::m/project-status tx-set-project-status)
(ipc->tx ::m/project-found tx-project-found)
(ipc->tx ::m/state-loaded tx-state-loaded)

(defmethod handle-ipc ::m/state-saved [r msg])

(defn persist-state [state]
  (let [disk-state
        {:projects
         (->> (::m/project-id state)
              (vals)
              (map #(select-keys % [::m/project-id
                                    ::m/project-path
                                    ::m/project-name
                                    ::m/project-short-path]))
              (into []))}]

    (ipc-send ::m/state-save {:data disk-state})
    state))

(defn update-project-list [state]
  (let [list
        (->> (::m/project-id state)
             (vals)
             (sort-by ::m/project-name)
             (map #(vector ::m/project-id (::m/project-id %)))
             (into []))]
    (assoc state ::projects list)))

(defn add-project [state {::m/keys [project-id] :as project-data}]
  (if (get-in state [::m/project-id project-id])
    state
    (assoc-in state [::m/project-id project-id] (merge {::m/project-status :pending} project-data))))

(defn query-project-status [project-data]
  (ipc-send ::m/project-status
    {:project-id (::m/project-id project-data)
     :project-path (::m/project-path project-data)}))

(defn log-state [state]
  (js/console.log "current-state" state)
  state)

(fm/handle-mutation tx-project-found
  {:refresh
   (fn []
     [::active-project
      ::projects])
   :state-action
   (fn [state env {:keys [data] :as params}]
     (query-project-status data)
     (-> state
         (add-project data)
         (persist-state)
         (update-project-list)
         (assoc ::active-project [::m/project-id (::m/project-id data)])
         (log-state)))})

(defn merge-saved-projects [state projects]
  (reduce
    (fn [state {::m/keys [project-id] :as data}]
      (update-in state [::m/project-id project-id] merge data))
    state
    projects))

(fm/handle-mutation tx-state-loaded
  {:refresh
   (fn []
     [::projects])

   :state-action
   (fn [state env {:keys [data] :as params}]
     (let [{:keys [projects]} data]
       (doseq [proj projects]
         (query-project-status proj))

       (-> state
           (merge-saved-projects projects)
           (update-project-list)
           (log-state))))})

(fm/handle-mutation tx-set-project-status
  {:refresh
   (fn [env params]
     [::m/project-status])

   :state-action
   (fn [state env {:keys [project-id data] :as params}]
     (update-in state [::m/project-id project-id] merge data))})

(fm/handle-mutation tx-proc-exit
  {:refresh
   (fn [env params]
     [::m/project-status])

   :state-action
   (fn [state env {:keys [project-id exit-code] :as params}]
     (update-in state [::m/project-id project-id]
       (fn [x]
         (-> x
             (dissoc ::m/project-server-url ::m/project-pid)
             (merge {::m/project-status :inactive
                     ::m/proc-exit-code exit-code})))))})

(fm/handle-mutation tx-select-project
  (fn [state {:keys [ref] :as env} {:keys [project-id] :as params}]
    (js/console.log "tx-select-project" ref project-id)
    (-> state
        (assoc ::sidebar false)
        (assoc-in (conj ref ::active-project) [::m/project-id project-id]))))

(fm/handle-mutation tx-forget-project
  {:refresh
   (fn [env params]
     [::projects])

   :state-action
   (fn [state env {:keys [project-id] :as params}]
     ;; FIXME: IPC kill if managed
     (-> state
         (update ::m/project-id dissoc project-id)
         (assoc ::active-project nil)
         (update-project-list)
         (persist-state)))})

(fm/handle-mutation tx-toggle-menu
  {:refresh
   (fn [env params]
     [::sidebar])
   :state-action
   (fn [state env params]
     (js/console.log "tx-toggle-menu")
     (update state ::sidebar not))})

(fm/handle-mutation tx-toggle-project-info
  (fn [state env {:keys [project-id] :as params}]
    (if (contains? params :force)
      (assoc-in state [::m/project-id project-id ::show-info] (:force params))
      (update-in state [::m/project-id project-id ::show-info] not))))

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
            (ipc-listen)
            (ipc-send ::m/state-load {}))

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

