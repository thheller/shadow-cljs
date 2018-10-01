(ns shadow.cljs.launcher.main
  (:require
    ["fs" :as fs]
    ["os" :as os]
    ["path" :as path]
    ["http" :as node-http]
    ["child_process" :as cp]
    ["electron" :as e :refer (app ipcMain)]
    [cognitect.transit :as transit]
    [shadow.cljs.model :as m]
    [cljs.tools.reader :as reader]
    [clojure.string :as str]))

;; FIXME: combine these
(defonce state-ref (atom {}))
(defonce windows-ref (atom {}))
(defonce procs-ref (atom {}))

(defn transit-read [msg]
  (let [r (transit/reader :json)]
    (transit/read r msg)))

(defn transit-str [msg]
  (let [w (transit/writer :json)]
    (transit/write w msg)))

(defn make-dirs [path]
  (when-not (fs/existsSync path)
    (make-dirs (path/dirname path))
    (fs/mkdirSync path)
    ))

(defn launcher-data-path []
  (let [home-dir
        (os/homedir)

        data-dir
        (case js/process.platform
          "darwin"
          (path/resolve home-dir "Library" "Preferences" "shadow-cljs")

          "win32"
          (path/resolve home-dir "AppData" "Roaming" "shadow-cljs")

          ;; linux etc
          (path/resolve home-dir ".shadow-cljs"))]

    (make-dirs data-dir)

    (path/resolve data-dir "launcher-state.edn")))

(defn load-saved-state! []
  (let [file (launcher-data-path)]
    (when (fs/existsSync file)
      (let [data
            (-> (fs/readFileSync file)
                (str)
                (reader/read-string))]

        (swap! state-ref merge data)))))

(defn save-state! []
  (let [file (launcher-data-path)
        s (pr-str @state-ref)]

    (fs/writeFileSync file s)))

(defn ipc-send
  ([op]
   (ipc-send op {}))
  ([op data]
   (let [data (assoc data ::m/op op)
         msg (transit-str data)]
     (js/console.log "ipc-main-out" (pr-str data))
     (some->
       (:main-win @windows-ref)
       (.-webContents)
       (.send "msg" msg)))))

(defn create-main-window []
  (let [{:keys [x y w h]}
        (:main-win @state-ref)

        main-win
        (-> {:width (or w 800)
             :height (or h 600)
             :minWidth 400
             :minHeight 400
             :title "shadow-cljs"
             :icon (path/resolve js/__dirname ".." "web" "img" "shadow-cljs.png")
             :show false
             :autoHideMenuBar true}
            (cond->
              (and x y)
              (assoc :x x :y y))
            (clj->js)
            (e/BrowserWindow.))]

    (.loadURL main-win (str "file://" (path/resolve js/__dirname ".." "web" "ui.html")))

    (.on main-win "closed"
      (fn []
        (swap! windows-ref dissoc :main-win)))

    (.on main-win "close"
      (fn []
        (let [[x y] (.getPosition main-win)
              [w h] (.getSize main-win)]

          (swap! state-ref update :main-win merge {:x x :y y :w w :h h})
          (save-state!))))

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

    (swap! windows-ref assoc :main-win main-win)

    main-win))

(defmulti handle-ipc ::m/op)

(defn project-short-path [full-path]
  (let [home (os/homedir)]
    (if (str/starts-with? full-path home)
      (str "~" (subs full-path (count home)))
      full-path
      )))

(defn did-project-find [paths]
  (when (seq paths)
    (let [project-dir (first paths)]
      (let [config (path/resolve project-dir "shadow-cljs.edn")]
        (if (fs/existsSync config)
          (ipc-send ::m/project-found {:data {::m/project-id project-dir
                                              ::m/project-path project-dir
                                              ::m/project-name (path/basename project-dir)
                                              ::m/project-short-path (project-short-path project-dir)}})
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

(defn wait-for-file [file cb]
  (fs/access file
    fs/constants.R_OK
    (fn [err]
      (if err
        (js/setTimeout #(wait-for-file file cb) 250)
        (cb)
        ))))

(defmethod handle-ipc ::m/project-start [{::m/keys [project-id project-path] :as query}]
  (if (get @procs-ref project-id)
    (ipc-send ::m/project-already-managed {::m/project-id project-id})
    (let [spawn-opts
          #js {:cwd project-path
               :shell true
               :windowsHide true}

          http-port-file
          (path/resolve project-path ".shadow-cljs" "http.port")

          ;; FIXME: don't go through npx, use re-use cli code instead
          ;; maybe even bundle deps downloader
          ^js proc
          (cp/spawn "npx" #js ["shadow-cljs" "server"] spawn-opts)]

      ;; in case an old one exists but the process is dead
      ;; FIXME: check if actually dead
      (when (fs/existsSync http-port-file)
        (fs/unlinkSync http-port-file))

      (swap! procs-ref assoc project-id proc)

      (ipc-send ::m/proc-starting
        {:project-id project-id
         :pid (.-pid proc)})

      (.on (. proc -stdout)
        "data"
        (fn [buf]
          (ipc-send ::m/proc-stdout
            {:project-id project-id
             :data (str buf)})))

      (.on (. proc -stderr)
        "data"
        (fn [buf]
          (ipc-send ::m/proc-stderr
            {:project-id project-id
             :data (str buf)})))

      (.on proc
        "error"
        (fn [^js err]
          (ipc-send ::m/proc-error
            {:project-id project-id
             :error-message (.-message err)})
          ))

      (.on proc
        "close"
        (fn [code]
          (ipc-send ::m/proc-exit {:project-id project-id
                                   :exit-code code})
          (swap! procs-ref dissoc project-id)))

      (wait-for-file http-port-file
        (fn []
          (let [port
                (-> (fs/readFileSync http-port-file)
                    (str)
                    (js/parseInt 10))]

            (ipc-send ::m/project-status
              {::m/project-id project-id
               ::m/project-status :managed
               ::m/project-pid (.-pid proc)
               ::m/project-server-url (str "http://localhost:" port)})
            ))))))

(defmethod handle-ipc ::m/state-load [_]
  (ipc-send ::m/state-loaded {:data @state-ref}))

(defmethod handle-ipc ::m/state-save [{:keys [data]}]
  (swap! state-ref merge data)
  (save-state!))

(defn start
  {:dev/after-load true}
  []
  (js/console.log "code reloaded"))

(defn init []
  (when (.-requestSingleInstanceLock app)
    (when-not (.requestSingleInstanceLock app)
      (.quit app)))

  (load-saved-state!)

  (.on app "second-instance"
    (fn [event args work-dir]
      (doseq [[_ win] @windows-ref]
        (.focus win))))

  #_(js/process.on "uncaughtException"
      (fn [err]
        (js/console.error err)))

  (.on ipcMain "msg"
    (fn [event arg]
      (let [msg (transit-read arg)]
        (js/console.log "ipc-main" (pr-str msg))
        (handle-ipc msg)
        )))

  (.on app "ready" create-main-window)

  (.on app "window-all-closed"
    (fn []
      (when (not= "darwin" js/process.platform)
        ;; FIXME: do proper shutdown here
        (doseq [proc (vals @procs-ref)]
          (.. proc -stdin (end))
          (.kill proc))

        (.quit app))))

  (.on app "activate"
    (fn []
      (when-not (:main-win @windows-ref)
        (create-main-window)))))
