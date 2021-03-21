(ns shadow.cljs.ui.components.inspect
  (:require
    [goog.math :as math]
    [shadow.dom :as dom]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.dom-scheduler :as ds]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.ui.vlist :as vlist]
    [shadow.experiments.grove.ui.loadable :refer (refer-lazy)]
    [shadow.experiments.grove.keyboard :as keyboard]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.common :as common]
    ))

(refer-lazy shadow.cljs.ui.components.code-editor/codemirror)

(def svg-chevron-double-left
  (<< [:svg {:width "24" :height "24" :viewBox "0 0 24 24" :fill "none" :xmlns "http://www.w3.org/2000/svg"}
       [:path {:d "M11 19L4 12L11 5M19 19L12 12L19 5" :stroke "#374151" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]))

(def svg-chevron-left
  (<< [:svg {:width "24" :height "24" :viewBox "0 0 24 24" :fill "none" :xmlns "http://www.w3.org/2000/svg"}
       [:path {:d "M15 19L8 12L15 5" :stroke "#4A5568" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]))

(defn render-edn-limit [[limit-reached text]]
  (if limit-reached
    (str text " ...")

    text))

(defc ui-object-as-text [ident attr active?]
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
      (cond
        ;; not using codemirror initially since it wants to treat "Loading ..." as clojure code
        (not= :ready loading-state)
        (<< [:div.w-full.h-full.font-mono.border-t.p-4
             "Loading ..."])

        (keyword-identical? ::m/display-error! val)
        (<< [:div.w-full.h-full.font-mono.border-t.p-4
             (str (name attr) " request failed ...")])

        :else
        (codemirror {:value val
                     :clojure (not= attr ::m/object-as-str)
                     :cm-opts {:tabindex (if active? 0 -1)
                               :readOnly true
                               :autofocus false}})

        ))))

(defmulti render-view
  (fn [data active?]
    (get-in data [:summary :data-type]))
  :default ::default)

(defmethod render-view ::default [{:keys [summary]}]
  (<< [:div.p-4
       [:div.py-1.text-xl.font-bold "Object does not support Browser view."]]))

(defn render-simple [value]
  (<< [:div.border.bg-gray-200
       [:div.bg-white
        [:pre.border.p-4 (str value)]]]))

(defmethod render-view :string [object active?]
  (ui-object-as-text (:db/ident object) ::m/object-as-str active?))

(defmethod render-view :number [object active?]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn active?))

(defmethod render-view :boolean [object active?]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn active?))

(defmethod render-view :symbol [object active?]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn active?))

(defmethod render-view :keyword [object active?]
  (ui-object-as-text (:db/ident object) ::m/object-as-edn active?))

(defmethod render-view :nil [object active?]
  (<< [:div.flex-1
       [:textarea.w-full.h-full.font-mono.border-t.p-4
        {:readOnly true
         :tabindex -1}
        "nil"]]))

(def seq-vlist
  (vlist/configure :fragment-vlist
    {:item-height 22}
    (fn [{:keys [val] :as entry} {:keys [idx focus] :as opts}]
      (<< [:div
           {:class (if focus
                     "border-b flex bg-gray-200"
                     "border-b flex hover:bg-gray-100")
            :on-click {:e ::inspect-nav! :idx idx}}
           [:div
            {:class "pl-4 px-2 border-r text-right"
             :style {:width "60px"}}
            idx]
           [:div.px-2.flex-1.truncate
            (render-edn-limit val)]]))))

(defn render-seq
  [object active?]
  (seq-vlist {:ident (:db/ident object)
              :select-event {:e ::kb-select!}
              :tabindex (if active? 0 -1)}))

(defmethod render-view :vec [data active?]
  (render-seq data active?))

(defmethod render-view :set [data active?]
  (render-seq data active?))

(defmethod render-view :list [data active?]
  (render-seq data active?))

(def map-vlist
  (vlist/configure :fragment-vlist
    {:item-height 22
     :box-style
     {:display "grid"
      :grid-template-columns "min-content minmax(25%, auto)"
      :grid-row-gap "1px"
      :grid-column-gap ".5rem"}}
    (fn [{:keys [key val] :as entry} {:keys [idx focus] :as opts}]
      (<< [:div
           {:on-click {:e ::inspect-nav! :idx idx}
            :class
            (str "whitespace-no-wrap font-bold px-2 border-r truncate bg-gray-100"
                 (if focus
                   " bg-gray-200"
                   " hover:bg-gray-100"))}
           (render-edn-limit key)]
          [:div
           {:on-click {:e ::inspect-nav! :idx idx}
            :class "whitespace-no-wrap truncate"}
           (render-edn-limit val)])
      )))

(defmethod render-view :map
  [object active?]
  (map-vlist {:ident (:db/ident object)
              :select-event {:e ::kb-select!}
              :tabindex (if active? 0 -1)}))

(def lazy-seq-vlist
  (vlist/configure :lazy-seq-vlist
    {:item-height 22
     :show-more
     (fn [entry]
       (<< [:div "Show more ... " (pr-str entry)]))}

    (fn [{:keys [val] :as entry} {:keys [idx focus] :as opts}]
      (<< [:div.border-b.flex
           {:on-click {:e ::inspect-nav! :idx idx}}
           [:div.pl-4.px-2.border-r.text-right {:style {:width "60px"}} idx]
           [:div.px-2.flex-1.truncate (render-edn-limit val)]]))))

(defmethod render-view :lazy-seq
  [object active?]
  (lazy-seq-vlist {:ident (:db/ident object)
                   :tabindex (if active? 0 -1)}))

(def css-button-selected "mx-2 border bg-blue-400 px-4 rounded")
(def css-button "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded")

(defn view-as-button [current val label active?]
  (<< [:button
       {:class (if (= current val)
                 css-button-selected
                 css-button)
        :on-click {:e ::inspect-switch-display! :display-type val}
        :tabindex (if active? 0 -1)}
       label]))

(defc ui-object-panel
  {:supports?
   (fn [prev-args next-args]
     (= (first prev-args) (first next-args)))}

  [ident panel-idx active?]
  (bind object
    (sg/query-ident ident
      [:db/ident
       :summary
       :is-error
       :display-type]))

  (event ::go-first! [env _ _]
    (sg/run-tx env {:e ::m/inspect-set-current! :idx 0}))

  (event ::code-eval! [env {:keys [code]}]
    (sg/run-tx env {:e ::m/inspect-code-eval! :ident ident :panel-idx panel-idx :code code}))

  (event ::inspect-nav! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-nav! :ident ident :panel-idx panel-idx)))

  (event ::inspect-switch-display! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-switch-display! :ident ident)))

  (event ::kb-select! [env {:keys [idx]}]
    (sg/run-tx env {:e ::m/inspect-nav! :ident ident :idx idx :panel-idx panel-idx}))

  (render

    (let [{:keys [summary is-error display-type]} object
          {:keys [obj-type entries supports]} summary]

      (<< [:div.h-full.flex.flex-col.overflow-hidden {::keyboard/listen true}
           [:div {:class "flex font-mono font-bold items-center border-b border-b-200"}
            (when (pos? panel-idx)
              (<< [:div.cursor-pointer.py-2.pl-2.font-bold
                   {:on-click {:e ::go-first!}
                    :title "go back to tap history (key: alt+t)"}
                   svg-chevron-double-left]

                  [:div.cursor-pointer.py-2.px-2.font-bold
                   {:on-click {:e ::go-back! :panel-idx panel-idx}
                    :title "go back one (key: left)"}
                   svg-chevron-left]))

            [:div {:class (str "px-2 py-2" (when is-error " text-red-700"))} obj-type]
            (when entries
              (<< [:div.py-2.px-2 (str entries " Entries")]))]

           (case display-type
             :edn
             (ui-object-as-text ident ::m/object-as-edn active?)

             :pprint
             (ui-object-as-text ident ::m/object-as-pprint active?)

             ;; default
             (<< [:div.flex-1.overflow-hidden.font-mono
                  (render-view object active?)]))

           ;; FIXME: don't always show this
           [:div.bg-white.font-mono.flex.flex-col
            [:div.flex.font-bold.px-4.border-b.border-t-2.py-1.text-l
             [:div.flex-1 "Runtime Eval (use $o for current obj, ctrl+enter for eval)"]]
            [:div {:style {:height "120px"}}
             ;; must not autofocus, otherwise the scroll-anim breaks
             (codemirror
               {:submit-event {:e ::code-eval!}
                :cm-opts
                {:autofocus false
                 :tabindex (if active? 0 -1)}})]]

           [:div.flex.bg-white.py-2.px-4.font-mono.border-t-2
            [:div "View as: "]
            (when (contains? supports :fragment)
              (view-as-button display-type :browse "Browse" active?))
            (when (contains? supports :pprint)
              (view-as-button display-type :pprint "Pretty-Print" active?))
            (when (contains? supports :edn)
              (view-as-button display-type :edn "EDN" active?))]]
          ))))

(defc ui-tap-stream-item [object-ident {:keys [focus]}]
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

      (<< [:div
           {:class (str "font-mono border-b px-2 py-1 cursor-pointer hover:bg-gray-100" (when focus " bg-gray-200"))
            :on-click {:e ::inspect-object! :ident object-ident}}

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

(def tap-vlist
  (vlist/configure :tap-vlist
    {:item-height 46
     :key-fn identity}
    ;; FIXME: for reasons I can't remember this currently only accepts functions
    (fn [ident info]
      (ui-tap-stream-item ident info))))

(defc ui-tap-panel [item panel-idx active?]
  (event ::kb-select! [env {:keys [item]}]
    (sg/dispatch-up! env {:e ::inspect-object! :ident (:object-ident item)}))

  (render
    (<< [:div.p-2.font-bold.border-b.border-bg-gray-200 "Tap History"]
        [:div.flex-1.overflow-hidden
         (tap-vlist {:tabindex (if active? 0 -1)})]

        [:div.flex.bg-white.py-2.px-4.font-mono.border-t-2
         [:button
          {:class css-button
           :tabindex (if active? 0 -1)
           :on-click ::m/tap-clear!}
          "Clear"]])))

(defc ui-tap-latest-panel [item panel-idx active?]
  (bind {::m/keys [tap-latest]}
    (sg/query-root [::m/tap-latest]))

  (render
    (if-not tap-latest
      (<< [:div.p-8.text-2xl "No Taps yet. (tap> something) to see it here."])
      (ui-object-panel tap-latest panel-idx active?))))

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
          (let [{:keys [start target time-start] :as state} @state-ref
                t (- (js/Date.now) time-start)
                t-diff (/ t 200)]
            (cond
              (< t-diff 1)
              (let [now (math/lerp start target t-diff)] ;; FIXME: bezier?
                (set! @container-ref -scrollLeft now)
                (swap! state-ref assoc :frame (js/requestAnimationFrame animate-scroll)))

              :else
              (let [{:keys [callback]} state]
                (set! @container-ref -scrollLeft target)
                (swap! state-ref assoc :done true)
                (callback)))))]

    (fn [idx callback]
      (let [c @container-ref
            cw (.-clientWidth c)
            sl (.-scrollLeft c)
            t (* idx cw)]

        (swap! state-ref assoc
          :width cw
          :start sl
          :target t
          :callback callback
          :time-start (js/Date.now))

        (when (not= t sl)
          (js/requestAnimationFrame animate-scroll))))))

(defc ui-page []
  (bind {::m/keys [inspect] :as data}
    (sg/query-root [::m/inspect]))

  (bind {:keys [stack current]}
    inspect)

  (event ::inspect-object! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-object!)))

  (bind container-ref (sg/ref))

  (bind scroll-into-view
    ;; hacky way to scroll since smooth is too slow IMHO
    (scrollable-anim container-ref))

  (bind current-changed?
    (sg/track-change
      current
      (fn [env old new]
        (not= old new))))

  (hook
    (sg/effect current
      (fn []
        (when current-changed?
          (ds/read!
            (scroll-into-view current
              (fn []
                ;; FIXME: need to transfer focus to the active panel
                ;; not sure if this is better solved by DOM interop or passing
                ;; of props to panels and letting them figure out if they should focus or not?
                ;; FIXME: this doesn't find things most of the time due to suspended queries not rendering the element
                ;; need a better strategy for this, right now it relies on the scroll anim taking longer than the suspense
                ;; so it may be missed and keyboard appears to stop working since the old element is focused but not visible
                (let [active-panel-el
                      (-> @container-ref
                          (.-children)
                          (aget current))

                      kb-focus
                      (dom/query-one "[tabindex=\"0\"]" active-panel-el)]

                  ;; (js/console.log "focusing" active-panel-el kb-focus)

                  ;; never autofocus codemirror textarea since that will consume all future keyboard
                  ;; events and isn't easy to nav out of yet?
                  (when (and kb-focus (not= (.-tagName kb-focus) "TEXTAREA"))
                    ;; this probably violates all ARIA guidelines but don't know how else to
                    ;; shift focus from the otherwise not visible panels?
                    (.focus kb-focus))
                  ))))))))

  (event ::go-back! [env {:keys [panel-idx]}]
    (sg/run-tx env {:e ::m/inspect-set-current! :idx (dec panel-idx)}))

  (event ::keyboard/arrowleft [env _ e]
    (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
      (when (pos? current)
        (sg/run-tx env {:e ::m/inspect-set-current! :idx (dec current)})
        (dom/ev-stop e))))

  (event ::keyboard/ctrl+arrowleft [env _ e]
    (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
      (sg/run-tx env {:e ::m/inspect-set-current! :idx 0})
      (dom/ev-stop e)))

  (event ::keyboard/alt+t [env _ e]
    (sg/run-tx env {:e ::m/inspect-set-current! :idx 0})
    (dom/ev-stop e))

  (event ::keyboard/arrowright [env _ e]
    (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
      (when (> (count stack) (inc current))
        (sg/run-tx env {:e ::m/inspect-set-current! :idx (inc current)})
        (dom/ev-stop e))))

  (event ::keyboard/ctrl+arrowright [env _ e]
    (when-not (dom/ancestor-by-class (.-target e) "CodeMirror")
      (sg/run-tx env {:e ::m/inspect-set-current! :idx (-> stack count dec)})
      (dom/ev-stop e)))

  (render
    (<< [:div.flex-1.mt-4.flex.flex-col.overflow-hidden {::keyboard/listen true}
         ;; [:div.px-6.font-bold "current:" current]
         [:div.flex-1.flex.overflow-hidden {:dom/ref container-ref}
          (sg/simple-seq stack
            (fn [{:keys [type] :as item} idx]
              (let [active? (= current idx)]
                (<< [:div {:class "px-3 pb-3 flex-none h-full"
                           :style {:width "100%"}}
                     [:div.border.shadow.bg-white.h-full.flex.flex-col
                      (case type
                        :tap-panel
                        (ui-tap-panel item idx active?)

                        :tap-latest-panel
                        (ui-tap-latest-panel item idx active?)

                        :object-panel
                        (ui-object-panel (:ident item) idx active?)

                        (<< [:div (pr-str item)]))]]
                    ))))]])))
