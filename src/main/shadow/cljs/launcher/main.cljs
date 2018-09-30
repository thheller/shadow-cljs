(ns shadow.cljs.launcher.main
  (:require
    ["fs" :as fs]
    ["path" :as path]
    ["http" :as node-http]
    ["electron" :as e :refer (app ipcMain)]
    [cognitect.transit :as transit]
    [shadow.cljs.model :as m]))

(defn transit-read [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn transit-str [msg]
  (let [w (transit/writer :json)]
    (transit/write w msg)))

(defonce windows-ref
  (atom {}))

(defn ipc-send
  ([op]
   (ipc-send op {}))
  ([op data]
   (let [data (assoc data ::m/op op)
         msg (transit-str data)]
     (js/console.log "ipc-main-out" (pr-str data))
     (some->
       (:project-select @windows-ref)
       (.-webContents)
       (.send "msg" msg)))))

(defn create-project-select-window []
  (let [main-win
        (e/BrowserWindow.
          #js {:width 800
               :height 600
               :minWidth 300
               :minHeight 400
               :title "shadow-cljs"
               :icon (path/resolve js/__dirname ".." "web" "img" "shadow-cljs.png")
               :show false
               :autoHideMenuBar true})]

    (.loadURL main-win (str "file://" (path/resolve js/__dirname ".." "web" "ui.html")))

    (.on main-win "closed"
      (fn []
        (swap! windows-ref dissoc :project-select)))

    (.. main-win -webContents
      (on "new-window"
        (fn [event url]
          (.preventDefault event)
          (e/shell.openExternal url)
          )))

    (.on main-win "ready-to-show"
      (fn []
        (.show main-win)))

    (.. main-win -webContents (openDevTools #js {:mode "detach"}))

    (swap! windows-ref assoc :project-select main-win)

    main-win))

(defmulti handle-ipc ::m/op)

(defn did-project-find [paths]
  (when (seq paths)
    (let [project-dir (first paths)]
      (let [config (path/resolve project-dir "shadow-cljs.edn")]
        (if (fs/existsSync config)
          (ipc-send ::m/project-found {:path project-dir})
          (e/dialog.showErrorBox
            "shadow-cljs.edn not found"
            (str "There was no shadow-cljs.edn in:\n"
                 project-dir)))))))

(defmethod handle-ipc ::m/project-find [_]
  (-> {:title "Open Project Directory"
       :properties ["openDirectory"]}
      (clj->js)
      (e/dialog.showOpenDialog did-project-find)))

(defmethod handle-ipc ::m/project-create [_]
  (e/dialog.showMessageBox
    (-> {:type "info"
         :title "TBD"
         :message "Coming soon ... I hope ..."}
        (clj->js))))


(defmethod handle-ipc ::m/open-item [{:keys [path]}]
  (e/shell.openItem path))

(defmethod handle-ipc ::m/project-status [{::m/keys [project-id project-path] :as query}]
  (let [result-fn
        (fn [status data]
          (ipc-send ::m/project-status (assoc data
                                         ::m/project-status status
                                         ::m/project-id project-id)))

        server-port-file
        (path/resolve project-path ".shadow-cljs" "http.port")]

    (if-not (fs/existsSync server-port-file)
      (result-fn :inactive {})

      (let [server-port
            (-> (fs/readFileSync server-port-file)
                (str)
                (js/parseInt 10))

            server-url
            (str "http://localhost:" server-port)

            ^js req
            (node-http/request
              #js {:host "localhost"
                   :port server-port
                   :path "/"
                   :method "GET"
                   :timeout 1000})]

        (.on req "error"
          (fn [err]
            (result-fn :error {::m/project-status-error (str err)})))

        (.on req "timeout"
          (fn [err]
            (result-fn :timeout {})))

        (.on req "response"
          (fn [res]
            (let [status (.-statusCode res)]
              (if (= 200 status)
                (result-fn :running {::m/project-server-url server-url})
                (result-fn :error {::m/project-status-error (str "http-status:" status)})))
            (.abort req)))

        (.end req)))))

(defmethod handle-ipc ::m/project-start [{::m/keys [project-id project-path] :as query}]
  (js/console.log "starting" project-path))

(defn start
  {:dev/after-load true}
  []
  (js/console.log "code reloaded"))

(defn init []
  (when (.-requestSingleInstanceLock app)
    (when-not (.requestSingleInstanceLock app)
      (.quit app)))

  (.on app "second-instance"
    (fn [event args work-dir]
      (doseq [[_ win] @windows-ref]
        (.focus win))))

  (js/process.on "uncaughtException"
    (fn [err]
      (js/console.error err)))

  (.on ipcMain "msg"
    (fn [event arg]
      (let [msg (transit-read arg)]
        (js/console.log "ipc-main" (pr-str msg))
        (handle-ipc msg)
        )))

  (.on app "ready" create-project-select-window)

  (.on app "window-all-closed"
    (fn []
      (when (not= "darwin" js/process.platform)
        (.quit app))))

  (.on app "activate"
    (fn []
      (when-not (:project-select @windows-ref)
        (create-project-select-window)))))
