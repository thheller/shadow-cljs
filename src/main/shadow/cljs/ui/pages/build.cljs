(ns shadow.cljs.ui.pages.build
  (:require
    [goog.string.format]
    [goog.string :refer (format)]
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.transactions :as tx]))

(defn render-build-log [{:keys [log] :as build-status}]
  ;; FIXME: are these useful at all?
  (when (seq log)
    (html/div
      (html/div (str (count log) " Log messages"))
      (html/for [entry log]
        (html/div {:key entry} entry)))))

(defn render-compiling-status [{:keys [active] :as build-status}]
  (html/div
    (html/div "Compiling ...")

    (when (seq active)
      (html/div
        (html/for [{:keys [timing-id] :as item}
                   (->> (vals active)
                        (sort-by :timing-id))]
          (html/div {:key timing-id} (::m/msg item))
          )))
    ))

(defn render-source-line
  [el start-idx lines]
  (html/for [[idx text] (map-indexed vector lines)]
    (el (format "%4d | %s" (+ 1 idx start-idx) text))))

(defn render-build-warning [{:keys [source-excerpt file msg resource-name] :as warning}]
  (html/div
    (html/div (str "Warning in ")
      (html/a {:href file} resource-name))

    (if-not source-excerpt
      (util/dump warning)

      (let [{:keys [start-idx before line after]} source-excerpt]
        (s/source-excerpt-container
          (render-source-line s/source-line start-idx before)
          (render-source-line s/source-line-highlight (+ start-idx (count before)) [line])
          (s/source-line-msg (str "       " msg))
          (render-source-line s/source-line (+ start-idx 1 (count before)) after)
          )))))

(defn render-completed-status [{:keys [duration warnings] :as build-status}]
  (html/div
    (html/div
      (str (if (seq warnings) "!" "✔")
           " Compilation completed in " duration " seconds."))

    (when (seq warnings)
      (html/div
        (html/div (str (count warnings) " Warnings"))
        (html/for [[idx warning] (map-indexed vector warnings)]
          (html/div {:key idx}
            (render-build-warning warning)))))))

(defn render-failed-status [{:keys [report] :as build-status}]
  (html/div
    (html/div
      (str "X Compilation failed."))

    (html/pre report)))

(defn render-build-status [{:keys [status] :as build-status}]
  (case status
    :compiling
    (render-compiling-status build-status)

    :completed
    (render-completed-status build-status)

    :failed
    (render-failed-status build-status)

    :inactive
    (html/div "Watch not active.")

    :pending
    (html/div "Watch starting ...")

    (do (js/console.warn "unhandled status" build-status)
        (util/dump build-status))))

(defn render-build-status-detail [build-status]
  (util/dump build-status))

(defsc BuildOverview [this {::m/keys [build-id build-status build-config-raw build-worker-active] :as props}]
  {:ident
   [::m/build-id ::m/build-id]

   :query
   [::m/build-config-raw
    ::m/build-id
    ::m/build-status
    ::m/build-http-server
    ::m/build-worker-active]}

  (if-not build-id
    (html/div "Loading ...")
    (s/main-contents
      (s/page-title (name build-id))

      (s/build-section "Actions")
      (s/simple-toolbar
        (if build-worker-active
          (s/toolbar-actions
            (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-watch-compile {:build-id build-id})])} "force-compile")
            (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-watch-stop {:build-id build-id})])} "stop watch"))

          (s/toolbar-actions
            (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-watch-start {:build-id build-id})])} "start watch")
            (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-compile {:build-id build-id})])} "compile")
            (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-release {:build-id build-id})])} "release")
            )))

      (s/build-section "Status")
      (render-build-status build-status)

      #_(html/div
          (s/build-section "Config")
          (s/build-config
            (util/dump build-config-raw)
            )))))

(def ui-build-overview (fp/factory BuildOverview {:keyfn ::m/build-id}))

