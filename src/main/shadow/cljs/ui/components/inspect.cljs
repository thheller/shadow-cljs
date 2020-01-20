(ns shadow.cljs.ui.components.inspect
  (:require
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.main.vlist :as vlist]
    [shadow.cljs.model :as m]
    [fipp.edn :refer (pprint)]
    ))

(defn render-edn-limit [[limit-reached text]]
  (if limit-reached
    (str text " ...")

    text))

(defc ui-object-as-text [ident attr]
  [{::sg/keys [loading-state] :as data}
   (sg/query-ident ident
     [attr]
     {:suspend false})]

  (let [val (get data attr)]
    (<< [:div.flex-1.relative
         #_[:button.absolute.rounded-full.shadow-lg.p-4.bg-blue-200.bottom-0.right-0.m-4.mr-8
            {:on-click [::copy-to-clipboard val]
             :disabled (not= :ready loading-state)}
            "COPY"]
         [:textarea.w-full.h-full.font-mono.border-t.p-4
          {:readOnly true}
          (if (= :ready loading-state)
            val
            "Loading ...")]])))

(defmulti render-view
  (fn [data]
    (get-in data [:summary :data-type]))
  :default ::default)

(defmethod render-view ::default [{:keys [summary]}]
  (<< [:div.p-4
       [:div.py-1.text-xl.font-bold "Object does not support Browser view."]
       [:pre
        (with-out-str (pprint summary))]]))

(defn render-simple [value]
  (<< [:div.border.bg-gray-200
       [:div.bg-white
        [:pre.border.p-4 (str value)]]]))

(defmethod render-view :string [object]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn))

(defmethod render-view :number [object]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn))

(defmethod render-view :boolean [object]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn))

(defmethod render-view :symbol [object]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn))

(defmethod render-view :keyword [object]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn))

(defmethod render-view :nil [object]
  (<< [:div.flex-1
       [:textarea.w-full.h-full.font-mono.border-t.p-4
        {:readOnly true}
        "nil"]]))

(def seq-vlist
  (vlist/configure :fragment-vlist
    {:item-height 22}
    (fn [{:keys [val] :as entry} idx opts]
      (<< [:div.border-b.flex
           {:on-click [::inspect-nav! idx]}
           [:div.pl-4.px-2.border-r.text-right {:style {:width "60px"}} idx]
           [:div.px-2.flex-1.truncate (render-edn-limit val)]]))))

(defn render-seq
  [object]
  (seq-vlist {:ident (:db/ident object)}))

(defmethod render-view :vec [data]
  (render-seq data))

(defmethod render-view :set [data]
  (render-seq data))

(defmethod render-view :list [data]
  (render-seq data))

(comment
  (defstyled map-root :div [env]
    {:display "grid"
     :padding "0 1rem"
     :grid-template-columns "min-content minmax(25%, auto)"
     :grid-row-gap "1px"
     :grid-column-gap ".5rem"})

  (defstyled map-span :div [env]
    {:grid-column-start "span 2"})

  (defstyled map-entry :div [env]
    {:display "contents"
     :cursor "pointer"})

  ;; {:class "pl-4 pr-2 font-bold whitespace-no-wrap truncate"}
  (defstyled map-key :div [env]
    {:font-weight "bold"
     :padding "0 .25rem"
     :white-space "nowrap"
     :overflow "hidden"
     :text-overflow "ellipsis"
     :background-color "#fafafa"
     "&:hover"
     {:background-color "#eee"}})

  ;; {:class "pr-4 truncate"}
  (defstyled map-val :div [env]
    {:white-space "nowrap"
     :overflow "hidden"
     :text-overflow "ellipsis"}))

(def map-vlist
  (vlist/configure :fragment-vlist
    {:item-height 22}
    (fn [{:keys [key val] :as entry} idx opts]
      (<< [:div.border-b.flex
           {:on-click [::inspect-nav! idx]}
           [:div.whitespace-no-wrap.font-bold.pl-4.px-2.border-r.truncate.bg-gray-100.hover:bg-gray-300
            {:style {:width "50%"}}
            ;; FIXME: track key length and adjust css var accordingly?
            ;; truncating long keys sucks
            ;; maybe figure out if possible for vlist to support grid
            ;; kinda liked the previous grid setup .. although it didn't work in JVM webview
            ;; so might be good to figure out good alternative
            ;; want all values to align but the key should reserve only as much space as needed
            ;; and never more than 75% or so
            (render-edn-limit key)]
           [:div.whitespace-no-wrap.px-2.flex-1.truncate
            (render-edn-limit val)]])
      )))

(defmethod render-view :map
  [object]
  (map-vlist {:ident (:db/ident object)}))

(def svg-close
  ;; https://github.com/sschoger/heroicons-ui/blob/master/svg/icon-x-square.svg
  (sa/svg
    ;; FIXME: << should work but fails, think the automatic svg upgrade is buggy
    [:svg
     {:xmlns "http://www.w3.org/2000/svg"
      :viewBox "0 0 24 24"
      :width "24"
      :height "24"}
     [:path
      {:d "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5c0-1.1.9-2 2-2zm0 2v14h14V5H5zm8.41 7l1.42 1.41a1 1 0 1 1-1.42 1.42L12 13.4l-1.41 1.42a1 1 0 1 1-1.42-1.42L10.6 12l-1.42-1.41a1 1 0 1 1 1.42-1.42L12 10.6l1.41-1.42a1 1 0 1 1 1.42 1.42L13.4 12z"}]]))

(def css-button-selected "mx-2 border bg-blue-400 px-4 rounded")
(def css-button "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded")

(defn view-as-button [current val label]
  (<< [:button
       {:class (if (= current val)
                 css-button-selected
                 css-button)
        :on-click [::inspect-switch-display! val]}
       label]))

(defc ui-inspect []
  [{::m/keys [inspect] :as data}
   (sg/query-root
     [{::m/inspect
       [{:object
         [:db/ident
          :oid
          :summary]}
        :nav-stack
        :display-type]}])

   ;; FIXME: is display-type something the db needs to handle?
   ;; could just use a local setting with an atom?
   ::inspect-switch-display!
   (fn [env e display-type]
     (sg/run-tx env [::m/inspect-switch-display! display-type]))]

  (let [{:keys [object nav-stack display-type]} inspect
        {:keys [summary]} object
        {:keys [obj-type entries]} summary]

    (<< (when (seq nav-stack)
          (<< [:div.bg-white.py-4.font-mono
               (sg/render-seq nav-stack :idx
                 (fn [{:keys [idx key]}]
                   (<< [:div.px-4.cursor-pointer.hover:bg-gray-200
                        {:on-click [::inspect-nav-jump! idx]}
                        (str "<< " (if key (second key) idx))])))]))

        [:div {:class "flex bg-white py-1 px-2 font-mono border-b-2 text-l"}
         [:div {:class "px-2 font-bold"} obj-type]
         (when entries
           (<< [:div {:class "px-2 font-bold"} (str entries " Entries")]))

         [:div.flex-1] ;; fill up space
         [:div.text-right.cursor-pointer.font-bold.px-2
          {:on-click [::inspect-cancel!]}
          svg-close]]

        (case display-type
          :edn
          (ui-object-as-text (:db/ident object) ::m/object-as-edn)

          :pprint
          (ui-object-as-text (:db/ident object) ::m/object-as-pprint)

          :browse
          (<< [:div.flex-1.overflow-auto.font-mono (render-view object)]))

        [:div.flex.bg-white.py-2.px-4.font-mono.border-t-2
         [:div "View as: "]
         (view-as-button display-type :browse "Browse")
         (view-as-button display-type :pprint "Pretty-Print")
         (view-as-button display-type :edn "EDN")])))

(defc ui-tap-stream-item [{:keys [object-ident]}]
  [{:keys [summary rid obj-preview] :as data}
   (sg/query-ident object-ident [:oid :rid :obj-preview :summary])]

  (let [{:keys [ts ns line column label]} summary]
    (<< [:div.font-mono.border-b.px-2.py-1.cursor-pointer.hover:bg-gray-200
         {:on-click [::inspect-object! object-ident]}
         [:div.text-xs.text-gray-500
          (str "Runtime #" rid " " ts
               (when ns
                 (str " - " ns
                      (when line
                        (str "@" line
                             (when column
                               (str ":" column))))))
               (when label
                 (str " - " label)))]
         [:div.truncate (render-edn-limit obj-preview)]])))

(def tap-stream
  (sg/stream ::m/taps {}
    (fn [{:keys [type] :as item}]
      (case type
        :tap
        (ui-tap-stream-item item)

        (<< [:div (pr-str item)])))))

(defc ui-page []
  [{::m/keys [inspect-active?] :as data}
   (sg/query-root [::m/inspect-active?])

   ;; FIXME: this needs better handling
   ;; maybe something like useTransition?
   closing-ref
   (atom false)

   ::inspect-object!
   (fn [env e ident]
     (reset! closing-ref false)
     (sg/run-tx env [::m/inspect-object! ident]))

   ::inspect-nav!
   (fn [env e idx]
     (sg/run-tx env [::m/inspect-nav! idx]))

   ::inspect-nav-jump!
   (fn [env e idx]
     (sg/run-tx env [::m/inspect-nav-jump! idx]))

   ::inspect-cancel!
   (fn [env e]
     (when-not @closing-ref
       (reset! closing-ref true)
       (js/setTimeout #(sg/run-tx env [::m/inspect-cancel!]) 200)))

   closing?
   (sg/watch closing-ref)]

  (<< [:div.flex-1.overflow-hidden.bg-white.pt-4.relative
       ;; [:div.bg-gray-200.p-1.text-l "Tap Stream"]
       [:div.h-full tap-stream]
       [:div.border-t-2.absolute.bg-white.flex.flex-col
        ;; fly-in from the bottom, translateY not height so the embedded vlist
        ;; can get the correct height immediately and doesn't start with 0
        ;; should also be better performance wise I think
        {:style {:transition (str "transform .2s " (if closing? "ease-in" "ease-out"))
                 :transform (str "translateY("
                                 (if (or closing? (not inspect-active?))
                                   "100%" 0)
                                 ")")
                 :right 0
                 :bottom 0
                 :left 0
                 :top "200px"}}
        (when inspect-active?
          (ui-inspect))]]))
