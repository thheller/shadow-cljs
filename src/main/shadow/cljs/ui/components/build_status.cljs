(ns shadow.cljs.ui.components.build-status
  (:require
    [goog.string.format]
    [goog.string :refer (format)]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]))

(defn render-build-log [{:keys [log] :as build-status}]
  ;; FIXME: are these useful at all?
  (when (seq log)
    (<< [:div.pl-2.pb-2
         [:div.text-md.font-medium.mb-2 (str (count log) " Log messages")]
         (sg/simple-seq log
           (fn [entry]
             (<< [:pre.text-sm entry])))])))

(defn render-compiling-status [{:keys [active] :as build-status}]
  (<< [:div
       [:div "Compiling ..."]
       (when (seq active)
         (<< [:div
              (sg/keyed-seq
                (->> (vals active)
                     (sort-by :timing-id))
                :timing-id
                (fn [{:keys [timing-id] :as item}]
                  (<< [:div (::m/msg item)])))]))]))

(defn render-source-line
  [start-idx lines]
  (sg/simple-seq lines
    (fn [body idx]
      (<< [:pre (format "%4d | " (+ 1 idx start-idx)) body]))))

(defn render-build-warning [{:keys [source-excerpt file line column msg resource-name] :as warning}]
  (<< [:div.font-mono.border.shadow.bg-white.mb-2.overflow-x-auto ;; build-warning-container

       [:div.px-2.py-1.border-b ;; build-warning-title
        (str "Warning " (:warning warning) " in ")
        [:a {:ui/href file} resource-name]
        " at " line ":" column]

       [:div.font-bold.text-lg.p-2.border-b ;; build-warning-message
        msg]

       (if-not source-excerpt
         [:div.p-1
          (pr-str warning)]
         (let [{:keys [start-idx before line after]} source-excerpt]
           (<< [:div.p-1 ;; source-excerpt-container
                (render-source-line start-idx before)
                (if-not column
                  (<< [:pre.font-bold ;;source-line-highlight
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

                    (<< [:pre.font-bold ;; source-line-highlight
                         (format "%4d | " (+ 1 (count before) start-idx))
                         [:span ;; source-line-part
                          prefix]
                         [:span.border-b-2.border-red-700 ;; source-line-part-highlight
                          highlight]
                         [:span ;; source-line-part
                          suffix]])))

                (render-source-line (+ start-idx 1 (count before)) after)
                ])))]))

(defn render-completed-status [{:keys [duration warnings] :as build-status}]
  (<< [:div.text-md.font-medium.pt-2.pl-2 (str " Compiled in " duration " seconds.")]
      (when (seq warnings)
        (<< [:div.flex-1.overflow-auto
             [:div.text-xl.px-1.py-2 (str (count warnings) " Warnings")]
             (sg/simple-seq warnings render-build-warning)]))))

(defn render-failed-status [{:keys [report] :as build-status}]
  (<< [:div
       [:div "X Compilation failed."]
       [:pre report]]))

(defn render-build-status-short [{:keys [status] :as build-status}]
  (case status
    nil
    (<< [:div "Unknown, waiting for recompile."])

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

(defn render-build-status-full [{:keys [status] :as build-status}]
  (case status
    nil
    (<< [:div "Unknown, waiting for recompile."])

    :compiling
    (<< [:div.pb-2
         (render-compiling-status build-status)])

    :completed
    (<< [:div.pb-2
         (render-completed-status build-status)]
        [:div.flex-1.overflow-auto
         (render-build-log build-status)])

    :failed
    (<< [:div.pb-2
         [:div "X Compilation failed."]]
        [:div.p-2.flex-1.overflow-auto
         [:pre (:report build-status)]])

    :inactive
    (<< [:div "Watch not active."])

    :pending
    (<< [:div "Watch starting ..."])

    (do (js/console.warn "unhandled status" build-status)
        (pr-str build-status))))


(defn render-build-overview [overview]
  (when overview
    (<< [:div.p-2
         (pr-str overview)])))