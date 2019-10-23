(ns shadow.cljs.ui.pages.inspect
  (:require
    [cljs.pprint :refer (pprint)]
    [goog.object :as gobj]
    [com.fulcrologic.fulcro.application :as fa]
    [com.fulcrologic.fulcro.components :as fc :refer (defsc)]
    [com.fulcrologic.fulcro.algorithms.merge :as fam]
    [com.fulcrologic.fulcro.mutations :as fm :refer (defmutation)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.routing :as routing]
    [shadow.cljs.ui.fulcro-mods :as fmods :refer (deftx)]

    [cognitect.transit :as transit]
    [shadow.cljs.ui.env :as env]))

(defonce tool-ref (atom nil))

(defn reduce-> [init fn vals]
  (reduce fn init vals))

(defn send [obj]
  ;; (js/console.log "tool-send" obj)
  (let [w (transit/writer :json)
        json (transit/write w obj)]
    (.send (:socket @tool-ref) json)))

(defmutation request-fragment [{:keys [object-id idx] :as params}]
  (action [{:keys [state] :as env}]

    (let [runtime-id (get-in @state [::object-id object-id ::runtime-id])]
      (swap! state update-in [::object-id object-id :fragment idx] merge {})

      (send {:op :obj-request-view
             :runtime-id runtime-id
             :obj-id object-id
             :start idx
             :num 1
             :view-type :fragment
             :key-limit 100
             :val-limit 100})
      )))

(defmutation select-runtime [{:keys [runtime-id] :as params}]
  (action [{:keys [state] :as env}]
    (send {:op :request-tap-history :runtime-id runtime-id})
    (let [[_ current] (get-in @state [::ui-model/page-inspect 1 ::runtime])]
      (when (not= runtime-id current)
        (when current
          (send {:op :tap-unsubscribe :runtime-id current}))
        (send {:op :tap-subscribe :runtime-id runtime-id}))

      (swap! state
        (fn [state]
          (-> state
              (assoc-in [::ui-model/page-inspect 1 ::runtime] [::runtime-id runtime-id])
              (update-in [::runtime-id runtime-id] dissoc ::object)
              (update-in [::runtime-id runtime-id] assoc ::nav-stack [])))))))


(defmutation select-object [{:keys [object-id] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [runtime-id]
           :keys [summary fragment]
           :as obj}
          (get-in @state [::object-id object-id])]

      (swap! state update-in [::runtime-id runtime-id] merge
        {::object [::object-id object-id]
         ::nav-stack []})

      (when (and (contains? #{:map :vec :set} (:data-type summary))
                 (< (count fragment) 25))

        (send {:op :obj-request-view
               :runtime-id runtime-id
               :obj-id object-id
               :start 0
               ;; FIXME: decide on a better number, maybe configurable
               :num (min 25 (:count summary))
               :view-type :fragment
               :key-limit 100
               :val-limit 100})))

    ;; (js/console.log "select-object" params)
    ))

(defmutation nav-object [{:keys [object-id idx] :as params}]
  (action [{:keys [state] :as env}]
    (let [{::keys [runtime-id]} (get-in @state [::object-id object-id])]
      (swap! state update-in [::runtime-id runtime-id ::nav-stack] conj params)
      (send {:op :obj-request-nav
             :runtime-id runtime-id
             :obj-id object-id
             :idx idx}))))

(defmutation nav-stack-jump [{:keys [runtime-id object-id idx] :as params}]
  (action [{:keys [state] :as env}]

    (swap! state
      (fn [state]
        (-> state
            (assoc-in [::runtime-id runtime-id ::object] [::object-id object-id])
            (update-in [::runtime-id runtime-id ::nav-stack] subvec 0 idx))))
    ))

(defmutation switch-object-display [{wanted-display :display :keys [object-id] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [runtime-id] :as obj} (get-in @state [::object-id object-id])]

      (swap! state assoc-in [::object-id object-id ::display-type] wanted-display)

      (case wanted-display
        :pprint
        (when-not (contains? obj ::pprint)
          (send {:op :obj-request-view
                 :runtime-id runtime-id
                 :obj-id object-id
                 :view-type :pprint}))

        :edn
        (when-not (contains? obj ::edn)
          (send {:op :obj-request-view
                 :runtime-id runtime-id
                 :obj-id object-id
                 :view-type :edn}))

        ;; don't need to do anything for :browse
        nil))))

(defmulti handle-tool-msg
  (fn [env msg] (:op msg))
  :default ::default)

(defmutation tool-msg-tx [params]
  (action [env]
    (handle-tool-msg env params)))

(defn start-browser [owner]
  (let [{::fa/keys [state-atom runtime-atom]} env/app

        socket (js/WebSocket. (str "ws://" js/document.location.host "/api/tool"))

        svc
        {:owner owner
         :socket socket
         :state-ref state-atom}]

    (reset! tool-ref svc)

    (.addEventListener socket "message"
      (fn [e]
        (let [t (transit/reader :json)
              msg (transit/read t (.-data e))]

          ;; (js/console.log "tool-msg" msg)
          (fc/transact! env/app [(tool-msg-tx msg)]))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e)
        (send {:op :request-runtimes})))

    (.addEventListener socket "close"
      (fn [e]
        (js/console.log "tool-close" e)
        ))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))

    svc))

(defn render-edn-limit [[limit-reached text]]
  (if limit-reached
    (html/span (str text "..."))
    (html/span text)))

(defmulti render-view
  (fn [comp summary fragment]
    (:data-type summary))
  :default ::default)

(defmethod render-view ::default [this summary entries]
  (html/div
    "unsupported object type"
    (html/pre
      (with-out-str
        (pprint summary)))))

(defn render-simple [value]
  (html/div {:className "border bg-gray-200"}
    (html/div {:className "bg-white"}
      (html/pre {:className "border p-4"} value))))

(defmethod render-view :string
  [this {:keys [value]} entries]
  (render-simple value))

(defmethod render-view :number
  [this {:keys [value]} entries]
  (render-simple (str value)))

(defmethod render-view :boolean
  [this {:keys [value]} entries]
  (render-simple (str value)))

(defmethod render-view :symbol
  [this {:keys [value]} entries]
  (render-simple (str "Symbol: " value)))

(defmethod render-view :keyword
  [this {:keys [value]} entries]
  (render-simple (str "Keyword: " value)))

(defmethod render-view :nil
  [this summary entries]
  (render-simple "nil"))

(defmethod render-view :set
  [this {:keys [object-id obj-type count]} entries]
  (html/div {:className "border bg-gray-200"}
    (html/div {:className "flex"}
      (html/div {:className "p-2"} obj-type)
      (html/div {:className "p-2"} (str count " Items")))

    (html/div {:className "bg-white"}
      (html/for [idx (range count)
                 :let [{:keys [val] :as entry} (get entries idx)]]
        (html/div {:key idx :className "border-b"}

          (cond
            (nil? entry)
            (html/div
              {:onMouseEnter
               (fn [e]
                 (fc/transact! this [(request-fragment {:object-id object-id
                                                        :idx idx})]))}
              "not available, fetch")

            :else
            (html/div {:className "flex"
                       :onClick
                       (fn [e]
                         (fc/transact! this [(nav-object {:object-id object-id
                                                          :idx idx})]))}
              (html/div {:className "px-2 border-r"} idx)
              (html/div {:className "px-2 flex-1"} (render-edn-limit val)))))
        ))))

(defmethod render-view :vec
  [this {:keys [object-id obj-type count]} entries]
  (html/div {:className "border bg-gray-200"}
    (html/div {:className "flex"}
      (html/div {:className "p-2"} obj-type)
      (html/div {:className "p-2"} (str count " Items")))

    (html/div {:className "bg-white"}
      (html/for [idx (range count)
                 :let [{:keys [val] :as entry} (get entries idx)]]
        (html/div {:key idx :className "border-b"}

          (cond
            (nil? entry)
            (html/div
              {:onMouseEnter
               (fn [e]
                 (fc/transact! this [(request-fragment {:object-id object-id
                                                        :idx idx})]))}
              "not available, fetch")

            :else
            (html/div {:className "flex"
                       :onClick
                       (fn [e]
                         (fc/transact! this [(nav-object {:object-id object-id
                                                          :idx idx})]))}
              (html/div {:className "px-2 border-r"} idx)
              (html/div {:className "px-2 flex-1"} (render-edn-limit val)))))
        ))))

;; map with small enough keys
(defn render-compact-map
  [this {:keys [object-id obj-type count]} entries]
  (html/div {:className "border bg-gray-200"}
    (html/div {:className "flex"}
      (html/div {:className "p-2"} obj-type)
      (html/div {:className "p-2"} (str count " Entries")))

    (html/div {:className ""}
      (html/table {:className "bg-white w-full"}
        (html/colgroup
          (html/col {:width 1})
          (html/col))
        (html/tbody
          (html/for [idx (range count)
                     :let [{:keys [key val] :as entry} (get entries idx)]]
            (cond
              (nil? entry)
              (html/tr {:key idx :className "border"}
                (html/td {:colSpan 2
                          :onMouseEnter
                          (fn [e]
                            (fc/transact! this [(request-fragment {:object-id object-id
                                                                   :idx idx})]))} "not available, fetch"))

              ;; started loading
              (empty? entry)
              (html/tr {:key idx :className "border"}
                (html/td {:colSpan 2} "..."))

              ;; FIXME: make this smarter
              ;; can tell via (:datafied summary) + val (which is edn-limit)
              ;; if nav is even useful
              :else
              (html/tr {:key idx
                        :className "border hover:bg-gray-200"
                        :onClick
                        (fn [e]
                          (fc/transact! this [(nav-object {:object-id object-id
                                                           :idx idx
                                                           :key key})]))}
                (html/td
                  {:className "px-2 py-1 font-bold whitespace-no-wrap cursor-pointer"}
                  (render-edn-limit key))
                (html/td
                  {:className "px-2 py-1 cursor-pointer"}
                  (render-edn-limit val))))
            ))))))

;; map with some large/long keys
(defn render-map [this {:keys [object-id obj-type count]} entries]
  (html/div {:className "border bg-gray-200"}
    (html/div {:className "flex"}
      (html/div {:className "p-2"} obj-type)
      (html/div {:className "p-2"} (str count " Entries")))

    (html/div {:className "bg-white"}
      (html/for [idx (range count)
                 :let [{:keys [key val] :as entry} (get entries idx)]]
        (html/div {:key idx :className "border-b"}

          (cond
            (nil? entry)
            (html/div {:onMouseEnter
                       (fn [e]
                         (fc/transact! this [(request-fragment {:object-id object-id
                                                                :idx idx})]))}
              "not available, fetch")

            (empty? entry)
            (html/div {} "...")

            :else
            (html/div {:className "border"
                       :onClick
                       (fn [e]
                         (fc/transact! this [(nav-object {:object-id object-id
                                                          :idx idx
                                                          :key key})]))}
              (html/div {:className "px-2 py-1 font-bold cursor-pointer"}
                (render-edn-limit key))
              (html/div {:className "px-2 py-1 cursor-pointer"}
                (render-edn-limit val)))))
        ))))

(defmethod render-view :map
  [this summary entries]
  ;; FIXME: calculate this in data, not in render
  (let [has-large-keys?
        (->> entries
             (vals)
             (map :key)
             (map first)
             (some true?))]
    (if has-large-keys?
      (render-map this summary entries)
      (render-compact-map this summary entries))))

(defsc Runtime [this {::keys [runtime-id] :as props}]
  {:ident
   (fn []
     [::runtime-id runtime-id])

   :query
   (fn []
     [::runtime-id
      ::runtime-info])}

  (html/div "runtime" (pr-str props)))

(def ui-runtime (fc/factory Runtime {:keyfn ::runtime-id}))

(def object-list-item-td-classes
  "border p-1")

(defsc ObjectListItem [this {::keys [object-id] :as props} {:keys [onSelect]}]
  {:ident
   (fn []
     [::object-id object-id])

   :query
   (fn []
     [::object-id
      :edn-limit
      :summary])}

  (let [{:keys [summary edn-limit]} props
        {:keys [data-type obj-type ts count sorted datafied]} summary]

    (html/tr {:className "cursor-pointer hover:bg-gray-200"
              :onClick
              (fn [e]
                (onSelect object-id))}
      (html/td {:className object-list-item-td-classes} ts)
      (html/td {:className object-list-item-td-classes}
        (render-edn-limit edn-limit))
      (html/td {:className object-list-item-td-classes} (str data-type))
      (html/td {:className object-list-item-td-classes} obj-type)
      (html/td {:className object-list-item-td-classes} count)
      (html/td {:className object-list-item-td-classes} (str datafied))
      (html/td {:className object-list-item-td-classes} (str sorted))
      )))

(def ui-object-list-item (fc/factory ObjectListItem {:keyfn ::object-id}))

(defsc ObjectDetail
  [this {::keys [object-id] :as props}]
  {:ident
   (fn []
     [::object-id object-id])

   :query
   (fn []
     [::object-id
      ::display-type
      :edn-limit
      :summary
      :fragment
      :edn
      :pprint])}

  (let [{::keys [display-type]
         :keys [summary fragment edn-limit]
         :or {display-type :browse}}
        props]
    (html/div {:className "bg-white p-2"}
      (html/div {:className "flex items-center"}
        (html/div {:className "text-xl my-2 pr-2"} "Object")
        (html/button
          {:className "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded"
           :onClick #(fc/transact! this [(switch-object-display {:object-id object-id :display :browse})])}
          "Browse")
        (html/button
          {:className "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded"
           :onClick #(fc/transact! this [(switch-object-display {:object-id object-id :display :pprint})])}
          "Pretty-Print")
        (html/button
          {:className "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded"
           :onClick #(fc/transact! this [(switch-object-display {:object-id object-id :display :edn})])}
          "EDN"))
      (case display-type
        :edn
        (html/div {:className "font-mono border p-4"}
          (:edn props "Loading ..."))

        :pprint
        (html/pre {:className "font-mono border p-4"}
          (:pprint props "Loading ..."))

        :browse
        (if-not summary
          (html/div "no summary")
          (html/div {:className "font-mono"}
            (render-view this (assoc summary :object-id object-id :edn-limit edn-limit) fragment)))))))

(def ui-object-detail (fc/factory ObjectDetail {:keyfn ::object-id}))

(defsc RuntimeDetail
  [this {::keys [runtime-id runtime-info objects object nav-stack] :as props}]
  {:ident
   (fn []
     [::runtime-id runtime-id])

   :query
   (fn []
     [::runtime-id
      ::runtime-info
      {::objects (fc/get-query ObjectListItem)}
      {::object (fc/get-query ObjectDetail)}
      ::nav-stack])}

  (let [select-object
        (fn [object-id]
          (fc/transact! this [(select-object {:runtime-id runtime-id
                                              :object-id object-id})]))]

    (html/div
      (html/div {:className "bg-white p-2 mb-2"}
        (html/h2 {:className "text-xl my-2"} "Tap History")
        (html/table {:className "table-auto w-full font-mono"}
          (html/thead
            (html/tr
              (html/th {:className "text-left"} "ts")
              (html/th {:className "text-left"} "edn preview")
              (html/th {:className "text-left"} "data")
              (html/th {:className "text-left"} "type")
              (html/th {:className "text-left"} "count")
              (html/th {:className "text-left"} "datafied")
              (html/th {:className "text-left"} "sorted")
              ))
          (html/tbody
            (html/for [obj objects]
              (ui-object-list-item
                (fc/computed obj {:onSelect select-object}))))))

      (html/div {:className "bg-white p-2 mb-2"}
        (html/div {:className "text-xl my-2"} "Nav Stack")
        (html/div {:className "font-mono"}
          (html/for [[stack-idx entry] (map-indexed vector nav-stack)]
            (let [{:keys [object-id idx key]} entry]

              (html/div
                {:key stack-idx
                 :className "border p-2 mb-2 cursor-pointer hover:bg-gray-200"
                 :onClick
                 (fn [e]
                   (fc/transact! this [(nav-stack-jump {:idx stack-idx
                                                        :runtime-id runtime-id
                                                        :object-id object-id})]))}
                (if key
                  (second key)
                  idx))))))


      (when object
        (ui-object-detail object)
        ))))

(def ui-runtime-detail (fc/factory RuntimeDetail {:keyfn ::runtime-id}))

(defsc Page [this {::keys [runtimes runtime] :as props}]
  {:ident
   (fn []
     [::ui-model/page-inspect 1])

   :query
   (fn []
     [{::runtimes (fc/get-query Runtime)}
      {::runtime (fc/get-query RuntimeDetail)}])

   :initLocalState
   (fn [this _]
     (when-not @tool-ref
       (start-browser this))

     {})

   :componentWillUnmount
   (fn [this]
     ;; FIXME: this should maybe disconnect the socket?
     )

   :initial-state
   (fn [p]
     {})}

  (s/main-contents
    (html/div {:className "bg-white p-2 mb-2"}
      (html/div {:className "text-xl my-2"} "Runtimes")
      (html/for [{::keys [runtime-id runtime-info] :as runtime} runtimes]
        (html/div
          {:key runtime-id
           :className "cursor-pointer p-2 border font-mono"
           :onClick
           (fn [e]
             (fc/transact! this [(select-runtime {:runtime-id runtime-id})]))}
          (pr-str runtime-info))))


    (if-not runtime
      (html/div "No runtime selected.")
      (ui-runtime-detail runtime))))

(def ui-page (fc/factory Page {}))

(defn init [])

(routing/register ::ui-model/root-router ::ui-model/page-inspect
  {:class Page
   :factory ui-page})

(defn route [tokens]
  (fc/transact! env/app
    [(routing/set-route
       {:router ::ui-model/root-router
        :ident [::ui-model/page-inspect 1]})]))

(defmethod handle-tool-msg ::default [_ msg]
  (js/console.warn "unhandled tool msg" msg))

(defmethod handle-tool-msg :welcome
  [{:keys [state] :as env} {:keys [tool-id]}]
  (swap! state assoc ::tool-id tool-id))

(defn as-idents [key ids]
  (into [] (map #(vector key %)) ids))

(defmethod handle-tool-msg :tap-history
  [{:keys [state]} {:keys [runtime-id obj-ids] :as msg}]

  (let [data @state]
    (doseq [obj-id obj-ids
            :when (not (get-in data [::object-id obj-id :summary]))]
      ;; FIXME: don't really need to request this for all types
      (send {:op :obj-request-view
             :runtime-id runtime-id
             :obj-id obj-id
             :view-type :edn-limit
             :limit 50})
      (send {:op :obj-request-view
             :runtime-id runtime-id
             :obj-id obj-id
             :view-type :summary})))

  (swap! state
    (fn [data]
      (-> data
          (assoc-in [::runtime-id runtime-id ::objects] (as-idents ::object-id obj-ids))
          (reduce->
            (fn [state obj-id]
              (update-in state [::object-id obj-id] merge {::object-id obj-id
                                                           ::runtime-id runtime-id}))
            obj-ids)))))

(defn add-first [prev head max]
  (into [head] (take (min max (count prev))) prev))

(defmethod handle-tool-msg :tap
  [{:keys [state]} {:keys [runtime-id obj-id] :as msg}]

  (send {:op :obj-request-view
         :runtime-id runtime-id
         :obj-id obj-id
         :view-type :edn-limit
         :limit 50})

  (send {:op :obj-request-view
         :runtime-id runtime-id
         :obj-id obj-id
         :view-type :summary})

  (swap! state
    (fn [state]
      (-> state
          (update-in [::runtime-id runtime-id ::objects] add-first [::object-id obj-id] 10)
          (update-in [::object-id obj-id] merge {::object-id obj-id
                                                 ::runtime-id runtime-id})))))


(def ts-options #js {:hour12 false
                     :hourCycle "h24"
                     :hour "2-digit"
                     :minute "2-digit"
                     :second "2-digit"})

(defn add-ts [{:keys [added-at] :as summary}]
  (let [date (js/Date. added-at)]
    (assoc summary :ts (.toLocaleTimeString date "en-US" ts-options))))

(defmethod handle-tool-msg :obj-view
  [{:keys [state]} {:keys [view-type view obj-id]}]
  (case view-type
    :fragment
    (swap! state update-in [::object-id obj-id :fragment] merge view)

    :summary
    (swap! state update-in [::object-id obj-id] assoc :summary (add-ts view))

    :edn
    (swap! state update-in [::object-id obj-id] assoc :edn view)

    :pprint
    (swap! state update-in [::object-id obj-id] assoc :pprint view)

    ;; default
    (swap! state update-in [::object-id obj-id] assoc view-type view)))

(defmethod handle-tool-msg :obj-nav-success
  [{:keys [state]} {:keys [runtime-id nav-obj-id] :as msg}]

  (send {:op :obj-request-view
         :runtime-id runtime-id
         :obj-id nav-obj-id
         :view-type :edn-limit
         :limit 50})

  (send {:op :obj-request-view
         :runtime-id runtime-id
         :obj-id nav-obj-id
         :view-type :summary})

  (swap! state
    (fn [state]
      (-> state
          (assoc-in [::runtime-id runtime-id ::object] [::object-id nav-obj-id])
          (update-in [::object-id nav-obj-id] merge {::runtime-id runtime-id
                                                     ::object-id nav-obj-id}))))
  )



(defmethod handle-tool-msg :runtimes
  [{:keys [state]} {:keys [runtimes]}]
  (let [db-data
        (->> runtimes
             (map (fn [{:keys [runtime-id] :as runtime-info}]
                    {::runtime-id runtime-id
                     ::runtime-info runtime-info}))
             (into []))]

    (swap! state fam/merge-component Page {::runtimes db-data})
    ))

(defn update-runtimes [state]
  (let [runtimes
        (->> (::runtime-id state)
             (vals)
             (map ::runtime-info)
             (sort-by :since)
             (reverse)
             (map (fn [{:keys [runtime-id]}]
                    [::runtime-id runtime-id]))
             (into []))]

    (assoc-in state [::ui-model/page-inspect 1 ::runtimes] runtimes)))

(defmethod handle-tool-msg :runtime-connect
  [{:keys [state]} {:keys [runtime-id runtime-info]}]
  (swap! state
    (fn [state]
      (-> state
          (assoc-in [::runtime-id runtime-id] {::runtime-id runtime-id
                                               ::runtime-info runtime-info})
          (update-runtimes)))))

(defmethod handle-tool-msg :runtime-disconnect
  [{:keys [state]} {:keys [runtime-id]}]
  (swap! state
    (fn [state]
      (-> state
          (update ::runtime-id dissoc runtime-id)
          (update-runtimes))))
  ;; FIXME: do something if runtime is currently selected
  )