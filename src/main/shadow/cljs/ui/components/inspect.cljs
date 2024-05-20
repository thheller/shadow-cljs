(ns shadow.cljs.ui.components.inspect
  (:require
    [clojure.string :as str]
    [goog.math :as math]
    [shadow.dom :as dom]
    [shadow.arborist :as sa]
    [shadow.arborist.dom-scheduler :as ds]
    [shadow.css :refer (css)]
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.grove.ui.vlist2 :as vlist]
    [shadow.grove.ui.loadable :refer (refer-lazy)]
    [shadow.grove.keyboard :as keyboard]
    [shadow.grove.ui.edn :as edn]
    [shadow.cljs :as-alias m]
    [shadow.cljs.ui.components.common :as common]
    [shadow.cljs.ui.db.inspect :as db]
    [shadow.cljs.ui.db.explorer :as explorer-db]
    ))

(refer-lazy shadow.cljs.ui.components.code-editor/codemirror)

(def svg-chevron-double-left
  (<< [:svg {:width "24" :height "24" :viewBox "0 0 24 24" :fill "none" :xmlns "http://www.w3.org/2000/svg"}
       [:path {:d "M11 19L4 12L11 5M19 19L12 12L19 5" :stroke "#374151" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]))

(def svg-chevron-left
  (<< [:svg {:width "24" :height "24" :viewBox "0 0 24 24" :fill "none" :xmlns "http://www.w3.org/2000/svg"}
       [:path {:d "M15 19L8 12L15 5" :stroke "#4A5568" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]))

(defn render-edn-limit [text]
  (if (str/starts-with? text "1,")
    (str (subs text 2) " ...")
    (subs text 2)))

(defn render-edn-limit-truncated [text]
  ;; just in case browser truncated the display
  ;; FIXME: maybe do this properly in a mouseover dialog showing edn-pretty?
  (<< [:span {:title (subs text 2)}
       (if (str/starts-with? text "1,")
         (str (subs text 2) " ...")
         (subs text 2))]))

(defc ui-object-as-text [object attr active?]
  (bind val
    (get object attr))

  (effect :mount [env]
    (db/maybe-load-object-as env object attr))

  (render
    #_[:button.absolute.rounded-full.shadow-lg.p-4.bg-blue-200.bottom-0.right-0.m-4.mr-8
       {:on-click [::copy-to-clipboard val]
        :disabled (not= :ready loading-state)}
       "COPY"]

    ;; not using codemirror initially since it wants to treat "Loading ..." as clojure code
    (if (nil? val)
      (<< [:div {:class (css :w-full :h-full :font-mono :border-t :p-4)}
           "Loading ..."])

      (if (keyword-identical? ::m/display-error! val)
        (<< [:div {:class (css :w-full :h-full :font-mono :border-t :p-4)}
             (str (name attr) " request failed ...")])

        (codemirror
          {:value val
           :clojure (not= attr ::m/object-as-str)
           :cm-opts {:tabindex (if active? 0 -1)
                     :readOnly true
                     :autofocus false}}))

      )))

(defc ui-object-as-edn [object active?]
  (bind val
    (:obj-edn object))

  (effect :mount [env]
    (db/maybe-load-object-as env object :obj-edn))

  (render
    ;; not using codemirror initially since it wants to treat "Loading ..." as clojure code
    (if (nil? val)
      (<< [:div {:class (css :w-full :h-full :font-mono :border-t :p-4)}
           "Loading ..."])

      (if (keyword-identical? ::m/display-error! val)
        (<< [:div {:class (css :w-full :h-full :font-mono :border-t :p-4)}
             "request failed ..."])

        (<< [:div {:class (css :w-full :h-full :overflow-auto)}
             (edn/render-edn-str val)])))))

(defmulti render-view
  (fn [object active?]
    (get-in object [:summary :data-type]))
  :default ::default)

(defmethod render-view ::default [{:keys [summary]}]
  (<< [:div {:class (css :p-4)}
       [:div {:class (css :py-1 :text-xl :font-bold)} "Object does not support Browser view."]]))

(defn render-simple [value]
  (<< [:div {:class (css :border :bg-gray-200)}
       [:div {:class (css :bg-white)}
        [:pre {:class (css :border :p-4)}
         (str value)]]]))

(defmethod render-view :string [object active?]
  (ui-object-as-text object :obj-str active?))

(defmethod render-view :number [object active?]
  (ui-object-as-text object :obj-edn active?))

(defmethod render-view :boolean [object active?]
  (ui-object-as-text object :obj-edn active?))

(defmethod render-view :symbol [object active?]
  (ui-object-as-text object :obj-edn active?))

(defmethod render-view :keyword [object active?]
  (ui-object-as-text object :obj-edn active?))

(defmethod render-view :nil [object active?]
  (<< [:div {:class (css :flex-1)}
       [:textarea
        {:class (css :w-full :h-full :font-mono :border-t :p-4)
         :readOnly true
         :tab-index -1}
        "nil"]]))

(defn render-seq
  [object active?]
  (vlist/render
    {:item-height 22
     :select-event {:e ::kb-select!}
     :tab-index (if active? 0 -1)}

    (fn read-fn [params]
      (sg/query db/fragment-vlist (:oid object) params))

    (fn [{:keys [val] :as entry} {:keys [idx focus] :as opts}]
      (let [$row
            (css :border-b :flex
              [:hover :bg-gray-100]
              ["&.focus" :bg-gray-200])

            $idx
            (css :pl-4 :px-2 :border-r :text-right {:width "60px"})

            $val
            (css :px-2 :flex-1 :truncate)]

        (<< [:div
             {:class (str $row (when focus " focus"))
              :on-click {:e ::inspect-nav! :idx idx}}
             [:div {:class $idx} idx]
             [:div {:class $val} (render-edn-limit val)]])))
    ))

(defmethod render-view :vec [data active?]
  (render-seq data active?))

(defmethod render-view :set [data active?]
  (render-seq data active?))

(defmethod render-view :list [data active?]
  (render-seq data active?))

(defmethod render-view :map
  [object active?]
  (vlist/render
    {:item-height 22
     :box-style
     {:display "grid"
      :grid-template-columns "min-content minmax(25%, auto)"
      :grid-row-gap "1px"
      :grid-column-gap ".5rem"}
     :select-event {:e ::kb-select!}
     :tab-index (if active? 0 -1)}

    (fn read-fn [params]
      ;; FIXME: dumb that we can't pass object here
      ;; fragment-vlist needs to read object from kv, otherwise it won't update
      (sg/query db/fragment-vlist (:oid object) params))

    (fn item-fn [{:keys [key val] :as entry} {:keys [idx focus] :as opts}]
      (let [$key
            (css
              :whitespace-nowrap :font-bold :px-2 :border-r :truncate :bg-gray-100
              [:hover :bg-gray-200]
              ["&:hover.focus" :bg-gray-300])

            $val
            (css :whitespace-nowrap :truncate
              [:hover :bg-gray-100])]

        (<< [:div
             {:class (str $key (when focus " focus"))
              :on-click {:e ::inspect-nav! :idx idx}}
             (render-edn-limit key)]
            [:div
             {:class $val
              :on-click {:e ::inspect-nav! :idx idx}}
             (render-edn-limit val)]))
      )))

(defmethod render-view :lazy-seq
  [object active?]
  (vlist/render
    {:item-height 22
     :tab-index (if active? 0 -1)
     :show-more
     (fn [entry]
       (<< [:div "Show more ... " (pr-str entry)]))}

    (fn [params]
      (sg/query db/lazy-seq-vlist object params))

    (fn [{:keys [val] :as entry} {:keys [idx focus] :as opts}]
      (let [$row
            (css
              :border-b :flex
              ["& > .idx" :pl-4 :px-2 :border-r :text-right {:width "60px"}]
              ["& > .val" :px-2 :flex-1 :truncate])]

        (<< [:div
             {:class $row
              :on-click {:e ::inspect-nav! :idx idx}}
             [:div.idx idx]
             [:div.val (render-edn-limit val)]])))))

(def $button-base (css :block :border :px-4 :rounded {:white-space "nowrap"}))
(def $button-selected (css :bg-blue-400))
(def $button (css :bg-blue-200 [:hover :bg-blue-400]))

(defn view-as-button [current val label active?]
  (<< [:div
       {:class [$button-base (if (= current val) $button-selected $button)]
        :on-click {:e ::inspect-switch-display! :display-type val}
        :tab-index (if active? 0 -1)}
       label]))

(defc ui-object-crumb [{:keys [nav? nav-idx nav-from oid] :as stack-item} panel-idx active?]
  (bind object
    (sg/kv-lookup ::m/object (or nav-from oid)))

  (bind label
    (if-not nav-idx
      (render-edn-limit-truncated (:edn-limit object))
      (if (= :map (get-in object [:summary :data-type]))
        (render-edn-limit-truncated (get-in object [:fragment nav-idx :key]))
        nav-idx
        )))

  (render
    (<< [:div {:class (css :border-r :p-2 :cursor-pointer :truncate {:max-width "160px"})
               :style/font-weight (if active? "600" "400")
               :style/font-style (if nav? "italic" "normal")
               :on-click {:e ::m/inspect-set-current! :idx panel-idx}}
         label])))

(defc ui-object-panel
  [^:stable oid panel-idx active?]
  (bind object
    (sg/kv-lookup ::m/object oid))

  (effect :mount [env]
    (db/maybe-load-summary env object)
    (db/maybe-load-obj-preview env object))

  (event ::go-first! [env _ _]
    (sg/run-tx env {:e ::m/inspect-set-current! :idx 0}))

  (event ::code-eval! [env {:keys [code]}]
    (sg/run-tx env
      {:e ::m/inspect-code-eval!
       :runtime-id (:runtime-id object)
       :ref-oid (:oid object)
       :panel-idx panel-idx
       :code code}))

  (event ::inspect-nav! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-nav! :oid oid :panel-idx panel-idx)))

  (event ::inspect-switch-display! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-switch-display! :oid oid)))

  (event ::kb-select! [env {:keys [idx]}]
    (sg/run-tx env {:e ::m/inspect-nav! :oid oid :idx idx :panel-idx panel-idx}))

  (render

    (let [{:keys [summary is-error display-type]} object
          {:keys [obj-type datafied data-type data-count supports]} summary

          $container
          (css :h-full :flex :flex-col :overflow-hidden)

          $header
          (css :flex :font-mono :font-bold :items-center :border-b
            ["& > *" :py-2]
            ["& > *:first-child" :pl-2]
            ["& > * + *" :px-2]
            ["& > .icon" :cursor-pointer :font-bold])]

      (<< [:div {:class $container ::keyboard/listen true}
           [:div {:class $header}

            [:div {:class (css :relative
                            ["& > .options" :hidden :absolute {:z-index 20}]
                            ["&:hover > .options" :block])}

             [:div {:class [$button-base $button]}
              (case display-type
                :edn "EDN (raw)"
                :edn-pretty "EDN (pretty)"
                :pprint "PPRINT"
                :str "STR"
                "BROWSER")]

             [:div {:class (css "options" :top-0 :left-0 :font-mono)}
              [:div {:class
                     (css :px-2 :py-2 :shadow-lg :bg-white
                       ["& > *:not(:last-child)" :mb-2])}
               (when (contains? supports :obj-fragment)
                 (view-as-button display-type :browse "BROWSER" active?))
               (when (contains? supports :obj-pprint)
                 (view-as-button display-type :pprint "PPRINT" active?))
               (when (contains? supports :obj-edn)
                 (view-as-button display-type :edn-pretty "EDN (pretty)" active?))
               (when (contains? supports :obj-edn)
                 (view-as-button display-type :edn "EDN (raw)" active?))
               (when (= :string data-type)
                 (view-as-button display-type :str "STR" active?))]]]

            ;; FIXME: add icon or something
            (when datafied
              (<< [:div "DATAFIED!"]))
            [:div {:class (when is-error "text-red-700")} obj-type]
            (when data-count
              (<< [:div (str data-count " Entries")]))

            [:div {:class (css :flex-1)}]]

           (case display-type
             :edn
             (ui-object-as-text object :obj-edn active?)

             :edn-pretty
             (ui-object-as-edn object active?)

             :pprint
             (ui-object-as-text object :obj-pprint active?)

             ;; default
             (<< [:div {:class (css :flex-1 :overflow-hidden :font-mono)}
                  (render-view object active?)]))

           ;; FIXME: don't always show this
           [:div {:class (css :bg-white :font-mono :flex :flex-col)}
            [:div {:class (css :flex :font-bold :px-4 :border-b :border-t :py-1 :text-sm)}
             [:div {:class (css :flex-1)} "Runtime Eval (use $o for current obj, ctrl+enter for eval)"]]
            [:div {:class (css {:height "120px"})}
             ;; must not autofocus, otherwise the scroll-anim breaks
             (codemirror
               {:submit-event {:e ::code-eval!}
                :cm-opts
                {:autofocus false
                 :tabindex (if active? 0 -1)}})]
            ]]
          ))))

(defc ui-tap-stream-item [oid {:keys [idx focus]}]
  (bind {:keys [summary edn-limit] :as object}
    (sg/kv-lookup ::m/object oid))

  (bind runtime
    (sg/kv-lookup ::m/runtime (:runtime-id object)))

  (effect :mount [env]
    (db/maybe-load-obj-preview env object)
    (db/maybe-load-summary env object))

  (render
    (let [{:keys [ns line column label]} summary
          {:keys [runtime-info runtime-id disconnected]} runtime

          $row
          (css
            :font-mono :border-b :px-2 :py-1 :cursor-pointer
            [:hover :bg-gray-100]
            ["&.disconnected" :cursor-not-allowed {:opacity "0.5"}]
            ["&.focus" :bg-gray-200]
            ["& > .header" :text-xs :text-gray-500]
            ["& > .val" :truncate])]

      (<< [:div
           {:class (str $row (when focus " focus") (when disconnected " disconnected"))
            :title (when disconnected "runtime disconnected")
            :on-click (when-not disconnected
                        {:e ::inspect-object! :oid oid})}

           [:div.header
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
           [:div.val
            (when edn-limit
              (render-edn-limit edn-limit))]]))))

(defn ui-tap-crumb [stack-item panel-idx active?]
  (<< [:div {:class (css :inline-block :border-r :p-2 :cursor-pointer :whitespace-nowrap)
             :style/font-weight (if active? "600" "400")
             :on-click {:e ::m/inspect-set-current! :idx panel-idx}}
       "Tap History"]))

(defc ui-tap-panel [item panel-idx active?]
  (event ::kb-select! [env {:keys [item] :as e}]
    (sg/dispatch-up! env {:e ::inspect-object! :ident item}))

  (render
    (let [$vlist (css :flex-1 :overflow-hidden)
          $buttons (css :flex :bg-white :py-2 :px-4 :font-mono :border-t-2)]

      (<< [:div {:class $vlist}

           (vlist/render
             {:item-height 46
              :key-fn identity
              :tab-index (if active? 0 -1)
              :select-event {:e ::kb-select!}}
             (fn [params]
               (sg/query db/tap-vlist params))
             (fn [ident info]
               (ui-tap-stream-item ident info)))]

          [:div {:class $buttons}
           [:button
            {:class [$button-base $button]
             :tab-index (if active? 0 -1)
             :on-click ::m/tap-clear!}
            "Clear"]]))))

(defn ui-tap-latest-crumb [stack-item panel-idx active?]
  (<< [:div {:class (css :inline-block :border-r :p-2 :cursor-pointer :whitespace-nowrap)
             :style/font-weight (if active? "600" "400")
             :on-click {:e ::m/inspect-set-current! :idx panel-idx}}
       "Tap Latest"]))

(defc ui-tap-latest-panel [item panel-idx active?]
  (bind tap-latest
    (sg/kv-lookup ::m/ui ::m/tap-latest))

  (render
    (if-not tap-latest
      (<< [:div {:class (css :p-8 :text-2xl)} "No Taps yet. (tap> something) to see it here."])
      (ui-object-panel tap-latest panel-idx active?))))

(defc ui-explore-var-details [runtime-id var]
  (bind description
    (sg/kv-lookup ::m/runtime runtime-id :explore-var-description))

  (render
    (let [{:keys [doc arglists]} description]

      (<< [:div {:class (css :border-t :flex)}
           [:div {:class (css :p-2 :flex-1 :text-xl :font-bold)} (str var)]
           [:div {:class (css :p-2) :on-click ::runtime-deselect-var!} common/icon-close]]
          (when (or (seq doc) (seq arglists))
            (<< [:div {:class (css :border-t :p-2 {:max-height "12%"} :overflow-y-auto)}
                 (when (seq doc)
                   (<< [:div {:class (css :p-2)} doc]))
                 (when (seq arglists)
                   (sg/simple-seq arglists
                     (fn [arglist]
                       (<< [:div {:class (css :px-2)} "(" (pr-str arglist) ")"]))))]))))))

(defc ui-explore-ns-details [runtime-id ns]
  (bind runtime-vars
    (sg/kv-lookup ::m/runtime runtime-id :runtime-vars ns))

  (bind explore-var
    (sg/kv-lookup ::m/runtime runtime-id :explore-var))

  (render
    (<< [:div {:class (css :flex-1 :flex :overflow-hidden :flex-col)}
         [:div {:class (css :flex-1 :overflow-y-auto :px-2)}
          (when (seq runtime-vars)
            (sg/simple-seq runtime-vars
              (fn [var]
                (<< [:div
                     {:class (when (= var explore-var) (css :font-bold))
                      :on-click {:e ::m/runtime-select-var!
                                 :runtime-id runtime-id
                                 :ns ns
                                 :var var}}
                     (name var)]))))]])))

(defn ui-explore-object-crumb [stack-item panel-idx active?]
  (<< [:div {:class (css :inline-block :border-r :p-2 :cursor-pointer :whitespace-nowrap)
             :style/font-weight (if active? "600" "400")
             :on-click {:e ::m/inspect-set-current! :idx panel-idx}}
       "Explore Object"]))

(defc ui-explore-object-panel
  [^:stable oid panel-idx active?]
  (bind object
    (sg/kv-lookup ::m/object oid))

  (effect :mount [env]
    (db/maybe-load-summary env object)
    (db/maybe-load-obj-preview env object))

  (event ::inspect-nav! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-nav! :ident oid :panel-idx panel-idx)))

  (event ::inspect-switch-display! [env tx]
    (sg/run-tx env (assoc tx :e ::m/inspect-switch-display! :ident oid)))

  (event ::kb-select! [env {:keys [idx]}]
    (sg/run-tx env {:e ::m/inspect-nav! :ident oid :idx idx :panel-idx panel-idx}))

  (render

    (let [{:keys [summary is-error display-type]} object
          {:keys [obj-type data-count supports]} summary]

      (<< [:div {:class (css :flex-1 :flex :flex-col :overflow-hidden {:min-height "52%"} :border-t)
                 ::keyboard/listen true}
           [:div {:class (css :flex :font-mono :font-bold :items-center :border-b)}
            [:div {:class (str (css :px-2 :py-2 ["&.error" :text-red-700]) (when is-error " error"))} obj-type]
            (when data-count
              (<< [:div {:class (css :py-2 :px-2)} (str data-count " Entries")]))]

           (case display-type
             :edn
             (ui-object-as-text object :obj-edn active?)

             :pprint
             (ui-object-as-text object :obj-pprint active?)

             ;; default
             (<< [:div {:class (css :flex-1 :overflow-hidden :font-mono)}
                  (render-view object active?)]))

           ;; FIXME: don't always show this

           [:div {:class (css :flex :bg-white :py-2 :px-4 :font-mono :border-t-2)}
            [:div "View as: "]
            (when (contains? supports :obj-fragment)
              (view-as-button display-type :browse "Browse" active?))
            (when (contains? supports :obj-pprint)
              (view-as-button display-type :pprint "Pretty-Print" active?))
            (when (contains? supports :obj-edn)
              (view-as-button display-type :edn "EDN" active?))]]
          ))))

(defn ui-explore-runtime-crumb [stack-item panel-idx active?]
  (<< [:div {:class (css :inline-block :border-r :p-2 :cursor-pointer :whitespace-nowrap)
             :style/font-weight (if active? "600" "400")
             :on-click {:e ::m/inspect-set-current! :idx panel-idx}}
       "Explore Runtime"]))

(defc ui-explore-runtime-panel [runtime-id panel-idx active?]
  (bind {:keys
         [explore-ns
          explore-var
          explore-var-object
          runtime-namespaces]
         :as runtime}
    (sg/kv-lookup ::m/runtime runtime-id))

  (effect :mount [env]
    (explorer-db/maybe-load-runtime-namespaces env runtime))

  (event ::code-eval! [env {:keys [code]}]
    (sg/run-tx env
      {:e ::m/inspect-code-eval!
       :runtime-id runtime-id
       :runtime-ns explore-ns
       :panel-idx panel-idx
       :code code}))

  (event ::runtime-deselect-var! [env ev]
    (sg/run-tx env
      {:e ::m/runtime-deselect-var!
       :runtime-id runtime-id}))

  (render
    (<< [:div {:class (css :flex-1 :overflow-hidden :flex :flex-col :bg-white)}
         [:div {:class (css :p-2 :font-bold :border-b)} "Exploring Runtime: #" runtime-id]
         [:div {:class (css :flex-1 :flex :overflow-hidden)}
          [:div {:class (css :overflow-y-auto)}
           (sg/keyed-seq runtime-namespaces identity
             (fn [ns]
               (<< [:div
                    {:class (str (css :px-2 ["&.selected" :font-bold])
                                 (when (= ns explore-ns) " selected"))
                     :on-click {:e ::m/runtime-select-namespace!
                                :runtime-id runtime-id
                                :ns ns}}
                    (name ns)])))]

          (if explore-ns
            (ui-explore-ns-details runtime-id explore-ns)
            (<< [:div {:class (css :flex-1 :p-4)} "Select a namespace ..."]))]

         (when explore-var
           (ui-explore-var-details runtime-id explore-var))

         (when explore-var-object
           (ui-explore-object-panel explore-var-object panel-idx active?))

         [:div {:class (css :bg-white :font-mono :flex :flex-col)}
          [:div {:class (css :flex :font-bold :px-4 :border-b :border-t-2 :py-1 :text-lg)}
           [:div {:class (css :flex-1)} "Runtime Eval (use $o for current obj, ctrl+enter for eval)"]]
          [:div {:style/height "120px"}
           ;; must not autofocus, otherwise the scroll-anim breaks
           (codemirror
             {:submit-event {:e ::code-eval!}
              :cm-opts
              {:autofocus false
               :tabindex (if active? 0 -1)}})]]

         ])))


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
  (bind {:keys [stack current]}
    (sg/kv-lookup ::m/ui ::m/inspect))

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
    (<< [:div
         {:class (css :flex-1 :mt-2 :p-2 :flex :flex-col :overflow-hidden)
          ::keyboard/listen true}
         ;; [:div.px-6.font-bold "current:" current]

         [:div {:class (css :flex-1 :flex :flex-col :overflow-hidden :shadow :bg-white :border)}

          [:div {:class (css :overflow-hidden :flex)}
           (sg/simple-seq stack
             (fn [{:keys [type] :as item} idx]
               (let [active? (= current idx)]
                 (case type
                   :tap-panel
                   (ui-tap-crumb item idx active?)

                   :tap-latest-panel
                   (ui-tap-latest-crumb item idx active?)

                   :object-panel
                   (ui-object-crumb item idx active?)

                   :explore-runtime-panel
                   (ui-explore-runtime-crumb item idx active?)

                   (<< [:div (pr-str item)])))

               ))]

          [:div
           {:class (css :flex-1 :flex :overflow-hidden)
            :dom/ref container-ref}
           (sg/simple-seq stack
             (fn [{:keys [type] :as item} idx]
               (let [active? (= current idx)]
                 (<< [:div {:class (css :flex-none :h-full :w-full)}
                      [:div {:class (css :border-t :h-full :flex :flex-col)}
                       (case type
                         :tap-panel
                         (ui-tap-panel item idx active?)

                         :tap-latest-panel
                         (ui-tap-latest-panel item idx active?)

                         :object-panel
                         (ui-object-panel (:oid item) idx active?)

                         :explore-runtime-panel
                         (ui-explore-runtime-panel (:runtime-id item) idx active?)

                         (<< [:div (pr-str item)]))]]
                     ))))]]])))
