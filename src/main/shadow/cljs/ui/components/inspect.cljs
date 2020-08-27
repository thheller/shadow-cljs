(ns shadow.cljs.ui.components.inspect
  (:require
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.ui.vlist :as vlist]
    [shadow.experiments.grove.ui.loadable :refer (refer-lazy)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.common :as common]
    [shadow.experiments.grove.keyboard :as keyboard]
    [shadow.dom :as dom]
    [goog.math :as math]
    ))

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

(defc ui-object-panel [{:keys [ident]} panel-idx]
  (bind object
    (sg/query-ident ident
      [:db/ident
       :summary
       :is-error
       :display-type]))

  (bind code-submit!
    (fn [env code]
      (sg/run-tx env [::m/inspect-code-eval! code ident panel-idx])))

  (render

    (let [{:keys [summary is-error display-type]} object
          {:keys [obj-type entries supports]} summary]

      (<< [:div {:class "flex bg-gray-200 p-2 font-mono font-bold"}
           [:div.cursor-pointer.pr-2.font-bold {:on-click [::go-back! panel-idx]} "<<"]
           [:div {:class (if is-error "px-2 text-red-700" "px-2")} obj-type]
           (when entries
             (<< [:div.px-2 (str entries " Entries")]))]

          (case display-type
            :edn
            (ui-object-as-text ident ::m/object-as-edn)

            :pprint
            (ui-object-as-text ident ::m/object-as-pprint)

            ;; default
            (<< [:div.flex-1.overflow-hidden.font-mono
                 (render-view object)]))

          ;; FIXME: don't always show this
          [:div.bg-white.font-mono.flex.flex-col
           [:div.flex.font-bold.px-4.border-b.border-t-2.py-1.text-l
            [:div.flex-1 "Runtime Eval (use $o for current obj, ctrl+enter for eval)"]]
           [:div {:style {:height "120px"}}
            (codemirror {:on-submit code-submit!
                         ;; must remain false, otherwise the scroll-right breaks
                         :autofocus false})]]

          [:div.flex.bg-white.py-2.px-4.font-mono.border-t-2
           [:div "View as: "]
           (when (contains? supports :fragment)
             (view-as-button display-type :browse "Browse"))
           (when (contains? supports :pprint)
             (view-as-button display-type :pprint "Pretty-Print"))
           (when (contains? supports :edn)
             (view-as-button display-type :edn "EDN"))]
          )))

  (event ::inspect-nav! [env key-idx]
    (sg/run-tx env [::m/inspect-nav! ident key-idx panel-idx]))

  (event ::inspect-switch-display! [env display-type]
    (sg/run-tx env [::m/inspect-switch-display! ident display-type])))

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

(defc ui-tap-panel [item panel-idx]
  (render
    (<< #_[:div.inset-0.flex.flex-col.overflow-hidden.h-full]
      [:div.p-2.bg-gray-200.font-bold "Tap History"]
      [:div.flex-1 tap-stream])))

;; really hacky way to scroll stuff
;; need to come up with better abstraction for this
(defn scrollable-anim [container-ref]
  (let [state-ref
        (atom {:width 0
               :current 0
               :target 0
               :start 0})

        animate-scroll
        (fn animate-scroll []
          (let [{:keys [start target time-start]} @state-ref
                t (- (js/Date.now) time-start)
                t-diff (/ t 200)]
            (cond
              (< t-diff 1)
              (let [now (math/lerp start target t-diff)] ;; FIXME: bezier?
                (set! @container-ref -scrollLeft now)
                (swap! state-ref assoc :frame (js/requestAnimationFrame animate-scroll)))

              :else
              (do (set! @container-ref -scrollLeft target)
                  (swap! state-ref assoc :done true)))))]

    (fn [idx]
      (let [c @container-ref
            cw (.-clientWidth c)
            sl (.-scrollLeft c)
            t (* idx cw)]

        (swap! state-ref assoc
          :width cw
          :start sl
          :target t
          :time-start (js/Date.now))

        (when (not= t sl)
          (js/requestAnimationFrame animate-scroll))))))

(defc ui-page []
  (bind {::m/keys [inspect] :as data}
    (sg/query-root [::m/inspect]))

  (bind {:keys [stack]}
    inspect)

  (event ::inspect-object! [env ident]
    (sg/run-tx env [::m/inspect-object! ident]))

  (bind container-ref (sg/dom-ref))

  (bind scroll-into-view
    ;; hacky way to scroll since smooth is too slow IMHO
    (scrollable-anim container-ref))

  (bind stack-count
    (count stack))

  (bind stack-diff
    (sg/track-change
      stack-count
      (fn [env old new]
        (- new (or old 0)))))

  (hook
    (sg/effect :render
      (fn []
        (when-not (zero? stack-diff)
          (scroll-into-view stack-count)))))

  (event ::go-back! [env panel-idx]
    (scroll-into-view (dec panel-idx)))

  (hook
    (keyboard/listen
      {"arrowleft"
       (fn [env e]
         (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
           (let [container @container-ref
                 c-width (.-clientWidth container)
                 c-scroll (.-scrollLeft container)
                 c-idx (int (/ c-scroll c-width))]

             (when (pos? c-idx)
               (scroll-into-view (dec c-idx))
               ))))

       "ctrl+arrowleft"
       (fn [env e]
         (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
           (scroll-into-view 0)))

       "ctrl+arrowright"
       (fn [env e]
         (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
           (scroll-into-view stack-count)))

       "arrowright"
       (fn [env e]
         (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
           (let [container @container-ref
                 c-width (.-clientWidth container)
                 c-scroll (.-scrollLeft container)
                 c-idx (int (/ c-scroll c-width))
                 n-idx (inc c-idx)]

             (when (> stack-count n-idx)
               (scroll-into-view n-idx)))))}))

  (render
    (<< [:div.flex-1.bg-white.pt-4.flex.flex-col.overflow-hidden
         #_[:div.px-6.font-bold "header"]
         [:div.flex-1.flex.overflow-hidden {:dom/ref container-ref}
          (sg/render-seq stack nil
            (fn [{:keys [type] :as item} idx]
              (<< [:div {:class "p-3 flex-none h-full"
                         :style {:width "100%"}}
                   [:div.border.shadow-lg.h-full.flex.flex-col
                    (case type
                      :tap-panel
                      (ui-tap-panel item idx)

                      :object-panel
                      (ui-object-panel item idx)

                      (<< [:div (pr-str item)]))]]
                  )))]])))
