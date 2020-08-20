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
  (bind {::sg/keys [loading-state] :as data}
    (sg/query-ident ident
      [attr]
      {:suspend false}))

  (render
    (let [val (get data attr)]
      #_[:button.absolute.rounded-full.shadow-lg.p-4.bg-blue-200.bottom-0.right-0.m-4.mr-8
         {:on-click [::copy-to-clipboard val]
          :disabled (not= :ready loading-state)}
         "COPY"]
      (if (= :ready loading-state)
        (codemirror {:value val
                     :clojure (not= attr ::m/object-as-str)
                     :cm-opts #js {:autofocus false}})
        ;; not using codemirror initially since it wants to treat "Loading ..." as clojure code
        (<< [:div.w-full.h-full.font-mono.border-t.p-4
             "Loading ..."])))))

(defmulti render-view
  (fn [data]
    (get-in data [:summary :data-type]))
  :default ::default)

(defmethod render-view ::default [{:keys [summary]}]
  (<< [:div.p-4
       [:div.py-1.text-xl.font-bold "Object does not support Browser view."]]))

(defn render-simple [value]
  (<< [:div.border.bg-gray-200
       [:div.bg-white
        [:pre.border.p-4 (str value)]]]))

(defmethod render-view :string [object]
  (ui-object-as-text (:db/ident object) ::m/object-as-str))

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

(def lazy-seq-vlist
  (vlist/configure :lazy-seq-vlist
    {:item-height 22
     :show-more
     (fn [entry]
       (<< [:div "Show more ... " (pr-str entry)]))}

    (fn [{:keys [val] :as entry} idx opts]
      (<< [:div.border-b.flex
           {:on-click [::inspect-nav! idx]}
           [:div.pl-4.px-2.border-r.text-right {:style {:width "60px"}} idx]
           [:div.px-2.flex-1.truncate (render-edn-limit val)]]))))

(defmethod render-view :lazy-seq
  [object]
  (lazy-seq-vlist {:ident (:db/ident object)}))

(def css-button-selected "mx-2 border bg-blue-400 px-4 rounded")
(def css-button "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded")

(defn view-as-button [current val label]
  (<< [:button
       {:class (if (= current val)
                 css-button-selected
                 css-button)
        :on-click [::inspect-switch-display! val]}
       label]))

(defc ui-inspect-item [ident idx]
  (bind object
    (sg/query-ident ident
      [:db/ident
       :summary
       :is-error
       :display-type]))

  (render

    (let [{:keys [summary is-error display-type]} object
          {:keys [obj-type entries]} summary

          class-header
          (if is-error
            "flex bg-white py-1 px-2 font-mono border-b-2 text-l text-red-700"
            "flex bg-white py-1 px-2 font-mono border-b-2 text-l")]

      (<< [:div.inset-0.h-full.overflow-hidden.flex.flex-col.shadow-2xl
           [:div {:class class-header}
            [:div {:class "px-2 font-bold"} obj-type]
            (when entries
              (<< [:div {:class "px-2 font-bold"} (str entries " Entries")]))]

           (case display-type
             :edn
             (ui-object-as-text ident ::m/object-as-edn)

             :pprint
             (ui-object-as-text ident ::m/object-as-pprint)

             ;; default
             (<< [:div.flex-1.overflow-auto.font-mono (render-view object)]))

           [:div.flex.bg-white.py-2.px-4.font-mono.border-t-2
            [:div "View as: "]
            (view-as-button display-type :browse "Browse")
            (view-as-button display-type :pprint "Pretty-Print")
            (view-as-button display-type :edn "EDN")]
           ])))

  (event ::inspect-nav! [env e idx]
    (sg/run-tx env [::m/inspect-nav! ident idx]))

  (event ::inspect-switch-display! [env e display-type]
    (sg/run-tx env [::m/inspect-switch-display! ident display-type])))

(defc ui-inspect []
  (bind {::m/keys [inspect] :as data}
    (sg/query-root
      [{::m/inspect
        [:nav-stack]}]))

  (bind {:keys [nav-stack]}
    inspect)

  (hook
    (keyboard/listen
      {"escape"
       (fn [env e]
         (sg/run-tx env [::m/inspect-cancel!]))

       ;; FIXME: problematic when in codemirror input, should have mechanism to ignore
       "ctrl+arrowleft"
       (fn [env e]
         (let [new-idx (-> nav-stack count dec dec)]
           (when (nat-int? new-idx)
             (sg/run-tx env [::m/inspect-nav-jump! new-idx]))))
       }))

  (bind code-submit!
    (fn [env code]
      (sg/run-tx env [::m/inspect-code-eval! code])))

  (bind peek-ref (atom nil))
  (bind peek (sg/watch peek-ref))

  (event ::peek-out! [env e]
    (reset! peek-ref nil))

  (event ::peek! [env e idx]
    (reset! peek-ref idx))

  (event ::inspect-nav-jump! [env e idx]
    (sg/run-tx env [::m/inspect-nav-jump! idx]))

  (render
    (<< [:div.h-full.flex-1.overflow-hidden.flex.flex-col
         [:div.flex.py-1
          [:div.pl-2 "Jump to: "]
          [:div.flex-1 {:on-mouseleave [::peek-out!]}
           (sg/simple-seq nav-stack
             (fn [_ idx]
               (<< [:div.inline-block.border.px-4.ml-2
                    {:on-mouseenter [::peek! idx]
                     :on-click [::inspect-nav-jump! idx]}
                    idx])))]

          [:div.text-right.cursor-pointer.font-bold.px-2
           {:on-click [::inspect-cancel!]}
           common/svg-close]]

         [:div.flex-1.flex.overflow-hidden.relative
          (sg/render-seq nav-stack :ident
            (fn [{:keys [ident]} idx]
              (let [peek-offset
                    (when peek
                      (let [diff (- idx peek)]
                        (when (pos? diff)
                          (str "translateX(" (+ 220 (* diff 60)) "px)"))))]

                (<< [:div.absolute.min-w-full.h-full.bg-white
                     {:style (-> {:transition "transform 250ms ease-in"
                                  :will-change "transform"
                                  :transform "translateX(0)"}
                                 (cond->
                                   peek-offset
                                   (assoc :transform peek-offset)))}
                     (ui-inspect-item ident idx)]))))]

         [:div.bg-white.font-mono.flex.flex-col
          [:div.flex.font-bold.px-4.border-b.border-t-2.py-1.text-l
           [:div.flex-1 "cljs.user - Runtime Eval (use $o for current obj, ctrl+enter for eval)"]]
          [:div {:style {:height "120px"}}
           (codemirror {:on-submit code-submit!})]]
         ])))

(defc ui-tap-stream-item [{:keys [object-ident]}]
  (bind {:keys [summary runtime obj-preview] :as data}
    (sg/query-ident object-ident
      [:oid
       {:runtime
        [:runtime-id
         :runtime-info]}
       :obj-preview
       :summary]))

  (render
    (let [{:keys [ns line column label]} summary
          {:keys [runtime-info runtime-id]} runtime]

      (<< [:div.font-mono.border-b.px-2.py-1.cursor-pointer.hover:bg-gray-200
           {:on-click [::inspect-object! object-ident]}
           [:div.text-xs.text-gray-500

            (str (:added-at-ts summary)
                 " - #" runtime-id
                 " " (:lang runtime-info)
                 " "
                 (when (= :cljs (:lang runtime-info))
                   (str "- " (:build-id runtime-info)
                        " - " (:host runtime-info)
                        " "))

                 (when ns
                   (str " - " ns
                        (when line
                          (str "@" line
                               (when column
                                 (str ":" column))))))
                 (when label
                   (str " - " label)))]
           [:div.truncate (render-edn-limit obj-preview)]]))))

(def tap-stream
  (sg/stream ::m/taps {}
    (fn [{:keys [type] :as item}]
      (case type
        :tap
        (ui-tap-stream-item item)

        (<< [:div (pr-str item)])))))

(defc ui-page []
  (bind {::m/keys [inspect-active?] :as data}
    (sg/query-root [::m/inspect-active?]))

  ;; FIXME: this needs better handling
  ;; maybe something like useTransition?
  (bind closing-ref
    (atom false))

  (event ::inspect-object! [env e ident]
    (reset! closing-ref false)
    (sg/run-tx env [::m/inspect-object! ident]))

  (event ::inspect-cancel! [env e]
    (when-not @closing-ref
      (reset! closing-ref true)
      (js/setTimeout #(sg/run-tx env [::m/inspect-cancel!]) 200)))

  (bind closing?
    (sg/watch closing-ref))

  (render
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
            (ui-inspect))]])))
