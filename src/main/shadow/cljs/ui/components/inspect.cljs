(ns shadow.cljs.ui.components.inspect
  (:require
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.ui.vlist :as vlist]
    [shadow.experiments.grove.ui.loadable :refer (refer-lazy)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.common :as common]
    [shadow.experiments.grove.keyboard :as keyboard]))

(refer-lazy shadow.cljs.ui.components.code-editor/codemirror)

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
    #_[:button.absolute.rounded-full.shadow-lg.p-4.bg-blue-200.bottom-0.right-0.m-4.mr-8
       {:on-click [::copy-to-clipboard val]
        :disabled (not= :ready loading-state)}
       "COPY"]
    (if (= :ready loading-state)
      (codemirror {:value val :cm-opts #js {:readOnly true :autofocus false}})
      ;; not using codemirror initially since it wants to treat "Loading ..." as clojure code
      (<< [:div.w-full.h-full.font-mono.border-t.p-4
           "Loading ..."]))))

(defmulti render-view
  (fn [data]
    (get-in data [:summary :data-type]))
  :default ::default)

(defmethod render-view ::default [{:keys [summary]}]
  (<< [:div.p-4
       [:div.py-1.text-xl.font-bold "Object does not support Browser view."]
       [:pre
        ;; pprint is too large, figure out a better way to display this
        (pr-str summary)]]))

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

(def map-vlist
  (vlist/configure :fragment-vlist
    {:item-height 22
     :box-style
     {:display "grid"
      :grid-template-columns "min-content minmax(25%, auto)"
      :grid-row-gap "1px"
      :grid-column-gap ".5rem"}}
    (fn [{:keys [key val] :as entry} idx opts]
      (<< [:div.whitespace-no-wrap.font-bold.px-2.border-r.truncate.bg-gray-100.hover:bg-gray-300
           {:on-click [::inspect-nav! idx]}
           (render-edn-limit key)]
          [:div.whitespace-no-wrap.truncate
           {:on-click [::inspect-nav! idx]}
           (render-edn-limit val)])
      )))

(defmethod render-view :map
  [object]
  (map-vlist {:ident (:db/ident object)}))



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
          :summary
          :is-error]}
        :nav-stack
        :display-type]}])

   ;; FIXME: is display-type something the db needs to handle?
   ;; could just use a local setting with an atom?
   ::inspect-switch-display!
   (fn [env e display-type]
     (sg/run-tx env [::m/inspect-switch-display! display-type]))

   code-submit!
   (fn [env code]
     (sg/run-tx env [::m/inspect-code-eval! code]))

   _
   (keyboard/listen
     {"escape"
      (fn [env e]
        (sg/run-tx env [::m/inspect-cancel!]))})]

  (let [{:keys [object nav-stack display-type]} inspect
        {:keys [summary is-error]} object
        {:keys [obj-type entries]} summary]

    (<< (when (seq nav-stack)
          (<< [:div.bg-white.py-4.font-mono
               (sg/render-seq nav-stack :idx
                 (fn [{:keys [idx key code]}]
                   (<< [:div.px-4.cursor-pointer.hover:bg-gray-200
                        {:on-click [::inspect-nav-jump! idx]}
                        (str "<< " (or code (and key (second key)) idx))])))]))

        [:div {:class (if is-error
                        "flex bg-white py-1 px-2 font-mono border-b-2 text-l text-red-700"
                        "flex bg-white py-1 px-2 font-mono border-b-2 text-l")}
         [:div {:class "px-2 font-bold"} obj-type]
         (when entries
           (<< [:div {:class "px-2 font-bold"} (str entries " Entries")]))

         [:div.flex-1] ;; fill up space
         [:div.text-right.cursor-pointer.font-bold.px-2
          {:on-click [::inspect-cancel!]}
          common/svg-close]]

        (case display-type
          :edn
          (ui-object-as-text (:db/ident object) ::m/object-as-edn)

          :pprint
          (ui-object-as-text (:db/ident object) ::m/object-as-pprint)

          :browse
          (<< [:div.flex-1.overflow-auto.font-mono (render-view object)]))

        (<< [:div.bg-white.font-mono.flex.flex-col
             [:div.flex.font-bold.px-4.border-b.border-t-2.py-1.text-l
              [:div.flex-1 "cljs.user - Runtime Eval (use $o for current obj, ctrl+enter for eval)"]]
             [:div {:style {:height "120px"}}
              (codemirror {:on-submit code-submit!})]])

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

  (<< [:div.flex-1.bg-white.pt-4.relative
       ;; [:div.bg-gray-200.p-1.text-l "Tap Stream"]
       [:div.h-full.overflow-auto tap-stream]
       [:div.border-t-2.absolute.bg-white.flex.flex-col
        ;; fly-in from the bottom, translateY not height so the embedded vlist
        ;; can get the correct height immediately and doesn't start with 0
        ;; should also be better performance wise I think
        {:style {:transition (str "transform .2s " (if closing? "ease-in" "ease-out"))
                 :transform (str "translateY("
                                 (if (or closing? (not inspect-active?))
                                   "103%" 0)
                                 ")")
                 :will-change "transform"
                 :box-shadow "0px 20px 20px 20px rgba(0,0,0,0.5)"
                 :right 0
                 :bottom 0
                 :left 0
                 :top "212px"}}
        (when inspect-active?
          (ui-inspect))]]))
