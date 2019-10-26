(ns shadow.cljs.ui.pages.inspect
  (:require
    [cljs.pprint :refer (pprint)]
    [goog.object :as gobj]
    [goog.functions :as gfn]
    [cognitect.transit :as transit]
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
    [shadow.cljs.ui.env :as env])

  (:import [goog.i18n DateTimeFormat]))

(def date-format (DateTimeFormat. "yyyy-MM-dd HH:mm:ss"))
(def time-format (DateTimeFormat. "HH:mm:ss"))

(defonce tool-ref (atom nil))

(defn reduce-> [init fn vals]
  (reduce fn init vals))

(defn send [obj]
  ;; (js/console.log "tool-send" obj)
  (let [w (transit/writer :json)
        json (transit/write w obj)]
    (.send (:socket @tool-ref) json)))

(defmutation request-fragment [{:keys [oid idx] :as params}]
  (action [{:keys [state] :as env}]

    (let [rid (get-in @state [::oid oid ::rid])]
      (swap! state update-in [::oid oid :fragment idx] merge {})

      (send {:op :obj-request-view
             :rid rid
             :oid oid
             :start idx
             :num 1
             :view-type :fragment
             :key-limit 100
             :val-limit 100})
      )))

(defmutation select-runtime [{:keys [rid] :as params}]
  (action [{:keys [state] :as env}]
    (send {:op :request-tap-history :rid rid :num 100})
    (let [[_ current] (get-in @state [::ui-model/page-inspect 1 ::runtime])]
      (when (not= rid current)
        (when current
          (send {:op :tap-unsubscribe :rid current}))
        (send {:op :tap-subscribe :rid rid}))

      (swap! state
        (fn [state]
          (-> state
              (assoc-in [::ui-model/page-inspect 1 ::runtime] [::rid rid])
              (update-in [::rid rid] dissoc ::object)
              (update-in [::rid rid] assoc ::nav-stack [])))))))

(defn maybe-fetch-initial-fragment [{::keys [rid oid] :keys [fragment summary] :as obj}]
  (let [max (min 35 (:count summary))]
    (when (and (contains? #{:map :vec :set} (:data-type summary))
               (< (count fragment) max))

      (send {:op :obj-request-view
             :rid rid
             :oid oid
             :start 0
             ;; FIXME: decide on a better number, maybe configurable
             :num max
             :view-type :fragment
             :key-limit 100
             :val-limit 100}))))

(defmutation select-object [{:keys [oid] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [rid] :as obj}
          (get-in @state [::oid oid])]

      (maybe-fetch-initial-fragment obj)

      (swap! state update-in [::rid rid] merge
        {::object [::oid oid]
         ::nav-stack []}))

    ;; (js/console.log "select-object" params)
    ))

(defmutation nav-object [{:keys [oid idx] :as params}]
  (action [{:keys [state] :as env}]
    (let [{::keys [rid]} (get-in @state [::oid oid])]
      (swap! state update-in [::rid rid ::nav-stack] conj params)

      ;; FIXME: show loading overlay instead? nav may take a while when doing actual nav
      ;; this just makes it go to blank state
      (swap! state assoc-in [::rid rid ::object] nil)

      (send {:op :obj-request-nav
             :rid rid
             :oid oid
             :idx idx}))))

(defmutation nav-stack-jump [{:keys [rid oid idx] :as params}]
  (action [{:keys [state] :as env}]

    (swap! state
      (fn [state]
        (-> state
            (assoc-in [::rid rid ::object] [::oid oid])
            (update-in [::rid rid ::nav-stack] subvec 0 idx))))
    ))

(defmutation switch-object-display [{wanted-display :display :keys [oid] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [rid] :as obj} (get-in @state [::oid oid])]

      (swap! state assoc-in [::oid oid ::display-type] wanted-display)

      (case wanted-display
        :pprint
        (when-not (contains? obj ::pprint)
          (send {:op :obj-request-view
                 :rid rid
                 :oid oid
                 :view-type :pprint}))

        :edn
        (when-not (contains? obj ::edn)
          (send {:op :obj-request-view
                 :rid rid
                 :oid oid
                 :view-type :edn}))

        ;; don't need to do anything for :browse
        nil))))

(defmulti handle-tool-msg
  (fn [env msg] (:op msg))
  :default ::default)

(defmutation tool-msg-tx [params]
  (action [env]
    (handle-tool-msg env params)))

(defn start-browser [connect-callback]
  (let [{::fa/keys [state-atom runtime-atom]} env/app

        socket (js/WebSocket. (str "ws://" js/document.location.host "/api/tool"))

        svc
        {:socket socket
         :state-ref state-atom}]

    (reset! tool-ref svc)

    ;; FIXME: fulcro/react can't keep up with too many messages that affect the UI
    ;; lots of taps will make the UI lag even if they aren't shown at all
    (.addEventListener socket "message"
      (fn [e]
        (let [t (transit/reader :json)
              msg (transit/read t (.-data e))]

          ;; (js/console.log "tool-msg" msg)
          (fc/transact! env/app [(tool-msg-tx msg)]))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e)
        (connect-callback)
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
    (str text " ...")

    text))

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
  [this {:keys [oid count]} entries]
  (html/div {}
    (html/for [idx (range count)
               :let [{:keys [val] :as entry} (get entries idx)]]
      (html/div {:key idx :className "border-b"}

        (cond
          (nil? entry)
          (html/div
            {:onMouseEnter
             (fn [e]
               (fc/transact! this [(request-fragment {:oid oid
                                                      :idx idx})]))}
            "not available, fetch")

          :else
          (html/div {:className "flex"
                     :onClick
                     (fn [e]
                       (fc/transact! this [(nav-object {:oid oid
                                                        :idx idx})]))}
            (html/div {:className "px-2 border-r"} idx)
            (html/div {:className "px-2 flex-1"} (render-edn-limit val)))))
      )))

(defmethod render-view :vec
  [this {:keys [oid obj-type count]} entries]
  (html/div {}
    (html/for [idx (range count)
               :let [{:keys [val] :as entry} (get entries idx)]]
      (html/div {:key idx :className "border-b"}

        (cond
          (nil? entry)
          (html/div
            {:onMouseEnter
             (fn [e]
               (fc/transact! this [(request-fragment {:oid oid
                                                      :idx idx})]))}
            "not available, fetch")

          :else
          (html/div {:className "flex"
                     :onClick
                     (fn [e]
                       (fc/transact! this [(nav-object {:oid oid
                                                        :idx idx})]))}
            (html/div {:className "px-2 border-r"} idx)
            (html/div {:className "px-2 flex-1"} (render-edn-limit val)))))
      )))

(defmethod render-view :map
  [this {:keys [oid obj-type] :as summary} entries]
  (html/div {:className "bg-white w-full"}
    (html/for [idx (range (:count summary))
               :let [{:keys [key val] :as entry} (get entries idx)
                     [key-limit-reached key-limit-text] key]]
      (cond
        (nil? entry)
        (html/div {:key idx :className "border"
                   :onMouseEnter
                   (fn [e]
                     (fc/transact! this [(request-fragment {:oid oid
                                                            :idx idx})]))}
          "not available, fetch")

        ;; started loading
        (empty? entry)
        (html/div {:key idx :className "border"} "...")

        ;; long key
        (or key-limit-reached
            (> (count key-limit-text) 22))
        (html/div {:key idx
                   :className "border-t hover:bg-gray-200"
                   :onClick
                   (fn [e]
                     (fc/transact! this [(nav-object {:oid oid
                                                      :idx idx
                                                      :key key})]))}
          (html/div {:className "px-4 py-1 font-bold whitespace-no-wrap cursor-pointer truncate"}
            (render-edn-limit key))
          (html/div {:className "pl-4 pr-4 py-1 cursor-pointer truncate"}
            "↳ " (render-edn-limit val)))

        ;; FIXME: make this smarter
        ;; can tell via (:datafied summary) + val (which is edn-limit)
        ;; if nav is even useful
        :else
        (html/div {:key idx
                   :className "flex border-t hover:bg-gray-200"
                   :onClick
                   (fn [e]
                     (fc/transact! this [(nav-object {:oid oid
                                                      :idx idx
                                                      :key key})]))}
          (html/div
            {:className "pl-4 pr-2 py-1 font-bold whitespace-no-wrap cursor-pointer truncate"
             :style {:flex "220px 0 0"}}
            (render-edn-limit key)
            #_(let [[limit-reached text] key
                    len (count text)]
                (if (> len 22)
                  (str (subs text 0 6) "..." (subs text (- len 13) len))
                  text)))
          (html/div
            {:className "pr-4 py-1 cursor-pointer truncate"}
            (render-edn-limit val)
            )))
      )))

(defsc Runtime [this {::keys [rid] :as props}]
  {:ident
   (fn []
     [::rid rid])

   :query
   (fn []
     [::rid
      ::runtime-info])}

  (html/div "runtime" (pr-str props)))

(def ui-runtime (fc/factory Runtime {:keyfn ::rid}))

(def object-list-item-td-classes
  "border p-1")

(defsc ObjectListItem [this {::keys [oid] :as props} {:keys [onSelect]}]
  {:ident
   (fn []
     [::oid oid])

   :query
   (fn []
     [::oid
      :edn-limit
      :summary])}

  (let [{:keys [summary edn-limit]} props
        {:keys [data-type obj-type ts count sorted datafied]} summary]

    (html/div {:className "flex font-mono border-b px-2 cursor-pointer hover:bg-gray-200"
               :onClick #(onSelect oid)}
      (html/div {:className "px-2"} ts)
      (html/div {:className "px-2 truncate"} (render-edn-limit edn-limit)))
    #_(html/tr {:className "cursor-pointer hover:bg-gray-200"
                :onClick
                (fn [e]
                  (onSelect oid))}
        (html/td {:className object-list-item-td-classes} ts)

        ;; (html/td {:className object-list-item-td-classes} (str data-type))
        ;; (html/td {:className object-list-item-td-classes} obj-type)
        ;; (html/td {:className object-list-item-td-classes} count)
        ;; (html/td {:className object-list-item-td-classes} (str datafied))
        ;; (html/td {:className object-list-item-td-classes} (str sorted))
        )))

(def ui-object-list-item (fc/factory ObjectListItem {:keyfn ::oid}))

(defsc ObjectHeader
  [this {::keys [oid] :as props}]
  {:ident
   (fn []
     [::oid oid])

   :query
   (fn []
     [::oid
      ::display-type
      :edn-limit
      :summary
      :fragment
      :edn
      :pprint])}

  (let [{::keys [display-type] :or {display-type :browse} :keys [summary]} props
        {:keys [data-type obj-type count]} summary]
    (html/div {:className "flex bg-white py-1 px-2 font-mono border-b-2 text-l"}
      #_(html/div {:className "pr-2 py-2 font-bold"} (str data-type))
      (html/div {:className "px-2 font-bold"} obj-type)
      (when count
        (html/div {:className "px-2 font-bold"} (str count " Entries")))
      )))

(def ui-object-header (fc/factory ObjectHeader {}))

(defsc ObjectFooter
  [this {::keys [oid] :as props}]
  {:ident
   (fn []
     [::oid oid])

   :query
   (fn []
     [::oid
      ::display-type
      :edn-limit
      :summary
      :fragment
      :edn
      :pprint])}

  (let [{::keys [display-type] :or {display-type :browse} :keys [summary]} props
        {:keys [data-type obj-type count]} summary]
    (html/div {:className "flex bg-white py-2 px-4 font-mono border-t-2"}
      #_(html/div {:className "pr-2 py-2 font-bold"} (str data-type))
      (html/div {:className ""} "View as: ")
      (html/button
        {:className "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded"
         :onClick #(fc/transact! this [(switch-object-display {:oid oid :display :browse})])}
        "Browse")
      (html/button
        {:className "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded"
         :onClick #(fc/transact! this [(switch-object-display {:oid oid :display :pprint})])}
        "Pretty-Print")
      (html/button
        {:className "mx-2 border bg-blue-200 hover:bg-blue-400 px-4 rounded"
         :onClick #(fc/transact! this [(switch-object-display {:oid oid :display :edn})])}
        "EDN")
      )))

(def ui-object-footer (fc/factory ObjectFooter {}))

(defsc ObjectDetail
  [this {::keys [oid] :as props}]
  {:ident
   (fn []
     [::oid oid])

   :query
   (fn []
     [::oid
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

    (case display-type
      :edn
      (html/div {:className "flex-1 font-mono bg-white"}
        (html/textarea {:className "w-full h-full font-mono border-t p-4"
                        :readOnly true
                        :value (:edn props "Loading ...")}))

      :pprint
      (html/div {:className "flex-1 font-mono bg-white"}
        (html/textarea {:className "w-full h-full font-mono border-t p-4"
                        :readOnly true
                        :value (:pprint props "Loading ...")}))
      #_(html/div {:className "flex-1 overflow-auto font-mono bg-white"}

          (html/pre {:className "font-mono border-t p-4"}
            (:pprint props "Loading ...")))

      :browse
      (html/div {:className "flex-1 overflow-auto font-mono bg-white"}

        (render-view this (assoc summary :oid oid :edn-limit edn-limit) fragment)))
    ))

(def ui-object-detail (fc/factory ObjectDetail {}))

(defsc RuntimePage
  [this {::keys [rid runtime-info objects object nav-stack] :as props}]
  {:ident
   (fn []
     [::rid rid])

   :initLocalState
   (fn [this _]
     (let [state-ref (atom {})]
       {:state-ref state-ref
        :scroll-ref
        (fn [^js node]
          (swap! state-ref assoc :node node)
          #_(when node
              (.addEventListener node "scroll"
                (gfn/debounce
                  (fn [e]
                    (let [max (.-scrollHeight node)
                          current (+ (.-scrollTop node) (.-offsetHeight node))

                          is-near-bottom
                          (> current (- max 25))]
                      (swap! state-ref assoc :is-near-bottom is-near-bottom)))
                  16))))}))

   ;; FIXME: I'd like to have the last tap at the bottom
   ;; auto scroll this to bottom has too many issues
   ;; so leaving it at top for now

   #_:componentDidUpdate
   #_(fn [this _ _]
       (let [state-ref (fc/get-state this :state-ref)
             {:keys [node is-near-bottom]} @state-ref]
         (when (and node is-near-bottom)
           (js/setTimeout
             (fn []
               (let [scroll-top (.-scrollHeight node)]
                 (js/console.log "setting scroll-top to" scroll-top)
                 (set! (.-scrollTop node) scroll-top)))
             ;; FIXME: do this properly
             ;; this is only required because componentDidUpdate fires
             ;; before all all the taps may have been added
             ;; it might render an empty div first because it is still
             ;; fetching the data, which means the scrollHeight is
             ;; lower than it needs to be
             ;; don't want to set a fixed height either though
             50))))

   :query
   (fn []
     [::rid
      ::runtime-info
      {::objects (fc/get-query ObjectListItem)}
      {::object (fc/get-query ObjectDetail)}
      ::nav-stack])}

  (let [select-object
        (fn [oid]
          (fc/transact! this [(select-object {:rid rid
                                              :oid oid})]))

        scroll-ref
        (fc/get-state this :scroll-ref)]

    (html/fragment
      (html/div {:className "bg-white mb-2 font-mono"}
        (html/div {:className "font-bold px-4 border-b py-1 text-l"} "Tap History")
        (html/div {:className "overflow-y-scroll"
                   :ref scroll-ref
                   :style #js {:height "220px"}}
          (cond
            (nil? objects)
            (html/div {:className "p-4"} "Loading ...")

            (empty? objects)
            (html/div {:className "p-4"} "No taps yet.")

            :else
            (html/for [obj objects]
              (ui-object-list-item
                (fc/computed obj {:onSelect select-object}))))))

      (when (seq nav-stack)
        (html/div {:className "bg-white py-4 font-mono"}
          (html/for [[stack-idx entry] (map-indexed vector nav-stack)]
            (let [{:keys [oid idx key]} entry]

              (html/div
                {:key stack-idx
                 :className "px-4 cursor-pointer hover:bg-gray-200"
                 :onClick
                 (fn [e]
                   (fc/transact! this [(nav-stack-jump {:idx stack-idx
                                                        :rid rid
                                                        :oid oid})]))}
                (str "<< "
                     (if key
                       (second key)
                       idx)))))))

      (when object
        (html/fragment
          (ui-object-header object)
          (ui-object-detail object)
          (ui-object-footer object)
          )))))

(def ui-runtime-page (fc/factory RuntimePage {}))

(defsc Page [this {::keys [runtimes runtime] :as props}]
  {:ident
   (fn []
     [::ui-model/page-inspect 1])

   :query
   (fn []
     [{::runtimes (fc/get-query Runtime)}])

   :initial-state
   (fn [p]
     {})}

  (html/div {:className "bg-white p-2 mb-2"}
    (html/div {:className "text-xl my-2"} "Runtimes")
    (html/for [{::keys [rid runtime-info] :as runtime} runtimes]
      (html/div
        {:key rid
         :className "p-2 border font-mono"}
        (html/a {:href (str "/inspect/" rid)} (pr-str runtime-info))))))

(def ui-page (fc/factory Page {}))

(defn init [])

(routing/register ::ui-model/root-router ::ui-model/page-inspect
  {:class Page
   :factory ui-page})

(routing/register ::ui-model/root-router ::rid
  {:class RuntimePage
   :factory ui-runtime-page})

(defn route [tokens]
  (if-not @tool-ref
    ;; wait for websocket connect first, then do actual route
    (do (fc/transact! env/app
          [(routing/set-route
             {:router ::ui-model/root-router
              :ident [::ui-model/page-loading 1]})])
        (start-browser #(route tokens)))

    (if (empty? tokens)
      (fc/transact! env/app
        [(routing/set-route
           {:router ::ui-model/root-router
            :ident [::ui-model/page-inspect 1]})])

      (let [[rid & more] tokens
            rid (js/parseInt rid)]
        (fc/transact! env/app
          [(select-runtime {:rid rid})
           (routing/set-route {:router ::ui-model/root-router
                               :ident [::rid (js/parseInt rid)]})])))))

(defmethod handle-tool-msg ::default [_ msg]
  (js/console.warn "unhandled tool msg" msg))

(defmethod handle-tool-msg :welcome
  [{:keys [state] :as env} {:keys [tid]}]
  (swap! state assoc ::tid tid))

(defn as-idents [key ids]
  (into [] (map #(vector key %)) ids))

(defn add-new-obj [state rid oid]
  (send {:op :obj-request-view
         :rid rid
         :oid oid
         :view-type :edn-limit
         :limit 100})

  (send {:op :obj-request-view
         :rid rid
         :oid oid
         :view-type :summary})

  (swap! state update-in [::oid oid] merge {::rid rid
                                            ::oid oid}))

(defmethod handle-tool-msg :tap-history
  [{:keys [state]} {:keys [rid oids] :as msg}]

  (let [data @state]
    (doseq [oid oids
            :when (not (get-in data [::oid oid :summary]))]
      (add-new-obj state rid oid)))

  (swap! state
    (fn [data]
      (-> data
          (assoc-in [::rid rid ::objects] (as-idents ::oid oids))
          ))))

(defn add-first [prev head max]
  (into [head] (take (min max (count prev))) prev))

(defn add-last [prev tail max]
  (let [new (conj prev tail)
        c (count new)]
    (if (>= (count new) max)
      (subvec new (- c max) c)
      new)))

(defmethod handle-tool-msg :tap
  [{:keys [state]} {:keys [rid oid] :as msg}]
  (add-new-obj state rid oid)
  (swap! state update-in [::rid rid ::objects] add-first [::oid oid] 100))

(defn add-ts [{:keys [added-at] :as summary}]
  (let [date (js/Date. added-at)]
    (assoc summary :ts (.format time-format date))))

(defmethod handle-tool-msg :obj-view
  [{:keys [state]} {:keys [view-type view oid rid] :as msg}]
  (case view-type
    :fragment
    (swap! state update-in [::oid oid :fragment] merge view)

    :summary
    (do (swap! state update-in [::oid oid] assoc :summary (add-ts view))
        (when (= oid (get-in @state [::rid rid ::object 1]))
          ;; received summary for visible object, auto fetch first fragment
          ;; happens on nav
          (maybe-fetch-initial-fragment (get-in @state [::oid oid]))))

    :edn
    (swap! state update-in [::oid oid] assoc :edn view)

    :pprint
    (swap! state update-in [::oid oid] assoc :pprint view)

    ;; default
    (swap! state update-in [::oid oid] assoc view-type view)))

(defmethod handle-tool-msg :obj-view-failed
  [{:keys [state]} {:keys [view-type oid rid e] :as msg}]
  (js/console.warn "remote-view failed" msg)
  (case view-type
    :edn
    (swap! state update-in [::oid oid] assoc :edn (str "Failed: " e))

    :pprint
    (swap! state update-in [::oid oid] assoc :pprint (str "Failed: " e))

    ;; default
    (js/alert (str "Object View failed for " view-type " - " e))
    ))

(defmethod handle-tool-msg :obj-nav-success
  [{:keys [state]} {:keys [rid nav-oid] :as msg}]
  (swap! state assoc-in [::rid rid ::object] [::oid nav-oid])
  (add-new-obj state rid nav-oid))

(defmethod handle-tool-msg :runtimes
  [{:keys [state]} {:keys [runtimes]}]
  (let [db-data
        (->> runtimes
             (map (fn [{:keys [rid] :as runtime-info}]
                    {::rid rid
                     ::runtime-info runtime-info}))
             (into []))]

    (swap! state fam/merge-component Page {::runtimes db-data})
    ))

(defn update-runtimes [state]
  (let [runtimes
        (->> (::rid state)
             (vals)
             (map ::runtime-info)
             (sort-by :since)
             (reverse)
             (map (fn [{:keys [rid]}]
                    [::rid rid]))
             (into []))]

    (assoc-in state [::ui-model/page-inspect 1 ::runtimes] runtimes)))

(defmethod handle-tool-msg :runtime-connect
  [{:keys [state]} {:keys [rid runtime-info]}]
  (swap! state
    (fn [state]
      (-> state
          (assoc-in [::rid rid] {::rid rid
                                 ::runtime-info runtime-info})
          (update-runtimes)))))

(defmethod handle-tool-msg :runtime-disconnect
  [{:keys [state]} {:keys [rid]}]
  (swap! state
    (fn [state]
      (-> state
          (update ::rid dissoc rid)
          (update-runtimes))))
  ;; FIXME: do something if runtime is currently selected
  )