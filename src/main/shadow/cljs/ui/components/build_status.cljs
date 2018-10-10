(ns shadow.cljs.ui.components.build-status
  (:require
    [goog.string.format]
    [goog.string :refer (format)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.util :as util]))

(defstyled error-container :pre
  [env]
  {:overflow "auto"})

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
          )))))

(defn render-source-line
  [el start-idx lines]
  (html/div
    (html/for [[idx body] (map-indexed vector lines)]
      (el {:key idx}
        (format "%4d | " (+ 1 idx start-idx))
        body))))

(defn render-build-warning [{:keys [source-excerpt file line column msg resource-name] :as warning}]
  (s/build-warning-container
    (s/build-warning-title (str "Warning in ")
      (html/a {:href file} resource-name)
      " at " line ":" column)
    (s/build-warning-message msg)

    (if-not source-excerpt
      (util/dump warning)

      (let [{:keys [start-idx before line after]} source-excerpt]
        (s/source-excerpt-container
          (render-source-line s/source-line start-idx before)
          (if-not column
            (s/source-line-highlight
              (format "%4d | " (+ 1 (count before) start-idx))
              (s/source-line-part-highlight line))
            (let [prefix (subs line 0 (dec column))
                  suffix (subs line (dec column))
                  [highlight suffix]
                  (if-let [m (.exec #"[^\w-]" suffix)]
                    (let [idx (.-index m)]
                      ;; (+ 1 "a") will have idx 0, make sure at least one char is highlighted
                      (if (pos? idx)
                        [(subs suffix 0 idx)
                         (subs suffix idx)]
                        [(subs suffix 0 1)
                         (subs suffix 1)]
                        ))
                    [suffix ""])]

              (s/source-line-highlight
                (format "%4d | " (+ 1 (count before) start-idx))
                (s/source-line-part prefix)
                (s/source-line-part-highlight highlight)
                (s/source-line-part suffix))))

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

    (error-container
      report)))

(defn render-build-status [{:keys [status] :as build-status}]
  (case status
    nil
    (html/div "Missing.")

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
