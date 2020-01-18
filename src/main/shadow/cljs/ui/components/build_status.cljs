(ns shadow.cljs.ui.components.build-status
  (:require
    [goog.string.format]
    [goog.string :refer (format)]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]))

(defn render-build-log [{:keys [log] :as build-status}]
  ;; FIXME: are these useful at all?
  (when (seq log)
    (<< [:div
         [:div (str (count log) " Log messages")]
         (sg/render-seq log identity
           (fn [entry]
             (<< [:div entry])))])))

(defn render-compiling-status [{:keys [active] :as build-status}]
  (<< [:div
       [:div "Compiling ..."]
       (when (seq active)
         (<< [:div
              (sg/render-seq
                (->> (vals active)
                     (sort-by :timing-id))
                :timing-id
                (fn [{:keys [timing-id] :as item}]
                  (<< [:div (::m/msg item)])))]))]))

(defn render-source-line
  [start-idx lines]
  (sg/render-seq (map-indexed vector lines) first
    (fn [[idx body]]
      (<< [:div (format "%4d | " (+ 1 idx start-idx)) body]))))

(defn render-build-warning [{:keys [source-excerpt file line column msg resource-name] :as warning}]
  (<< [:div ;; build-warning-container
       [:div ;; build-warning-title
        (str "Warning " (:warning warning) " in ")
        [:a {:href file} resource-name]
        " at " line ":" column]

       [:div ;; build-warning-message
        msg]

       (if-not source-excerpt
         (pr-str warning)
         (let [{:keys [start-idx before line after]} source-excerpt]
           (<< [:div ;; source-excerpt-container
                (render-source-line start-idx before)
                (if-not column
                  (<< [:div ;;source-line-highlight
                       (format "%4d | " (+ 1 (count before) start-idx))
                       [:div ;; source-line-part-highlight
                        line]])
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

                    (<< [:div ;; source-line-highlight
                         (format "%4d | " (+ 1 (count before) start-idx))
                         [:span ;; source-line-part
                          prefix]
                         [:span ;; source-line-part-highlight
                          highlight]
                         [:span ;; source-line-part
                          suffix]])))

                (render-source-line (+ start-idx 1 (count before)) after)
                ])))]))

(defn render-completed-status [{:keys [duration warnings] :as build-status}]
  (<< [:div
       [:div (str (if (seq warnings) "!" "✔") " Compilation completed in " duration " seconds.")]
       (when (seq warnings)
         (<< [:div
              [:div (str (count warnings) " Warnings")]
              (sg/render-seq warnings identity
                (fn [warning]
                  (render-build-warning warning)))]))]))

(defn render-failed-status [{:keys [report] :as build-status}]
  (<< [:div
       [:div "X Compilation failed."]
       [:pre report]]))

(defn render-build-status [{:keys [status] :as build-status}]
  (case status
    nil
    (<< [:div "Missing."])

    :compiling
    (render-compiling-status build-status)

    :completed
    (render-completed-status build-status)

    :failed
    (render-failed-status build-status)

    :inactive
    (<< [:div "Watch not active."])

    :pending
    (<< [:div "Watch starting ..."])

    (do (js/console.warn "unhandled status" build-status)
        (pr-str build-status))))
