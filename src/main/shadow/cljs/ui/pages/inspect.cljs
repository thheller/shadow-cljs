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
    [shadow.cljs.ui.env :as env]
    [shadow.debug :refer (?> ?-> ?->>)])

  (:import [goog.i18n DateTimeFormat]))

(def date-format (DateTimeFormat. "yyyy-MM-dd HH:mm:ss"))
(def time-format (DateTimeFormat. "HH:mm:ss"))

(defonce tool-ref (atom nil))
(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))

(defn reduce-> [init fn vals]
  (reduce fn init vals))

(defn cast! [msg]
  (js/console.log "ws-out" msg)
  (let [w (transit/writer :json)
        json (transit/write w msg)]
    (.send (:socket @tool-ref) json)))

(defn call! [msg callback-map]
  {:pre [(map? msg)
         (map? callback-map)]}
  (let [mid (swap! rpc-id-seq inc)]
    (swap! rpc-ref assoc mid callback-map)
    (cast! (assoc msg :mid mid))))

(defn as-idents [key ids]
  (into [] (map #(vector key %)) ids))

(defn add-ts [{:keys [added-at] :as summary}]
  (if-not added-at
    summary
    (let [date (js/Date. added-at)]
      (assoc summary :ts (.format time-format date)))))

(defn maybe-fetch-initial-fragment [state {::keys [rid oid] :keys [fragment summary] :as obj}]
  (when (contains? (:supports summary) :fragment)
    (let [max (min 35 (:entries summary))]
      (when (< (count fragment) max)
        (call! {:op :obj-request
                :rid rid
                :oid oid
                :request-op :fragment
                :start 0
                ;; FIXME: decide on a better number, maybe configurable
                :num max
                :key-limit 100
                :val-limit 100}
          {:obj-result
           (fn [{:keys [result] :as msg}]
             (swap! state update-in [::oid oid :fragment] merge result)
             (fc/transact! env/app [[::oid oid]])
             )})))))

(defn add-new-tap-obj
  "fetches enough info for new object to display as tap"
  [state rid oid]
  (swap! state update-in [::oid oid] merge {::rid rid
                                            ::oid oid})

  ;; welcome to callback hell
  (call! {:op :obj-describe
          :rid rid
          :oid oid}
    {:obj-summary
     (fn [{:keys [summary] :as msg}]
       (swap! state assoc-in [::oid oid :summary] (add-ts summary))

       (let [{:keys [supports]} summary]
         (when (contains? supports :edn-limit)
           (call! {:op :obj-request
                   :rid rid
                   :oid oid
                   :request-op :edn-limit
                   :limit 100}

             {:obj-result
              (fn [{:keys [result] :as msg}]
                (swap! state assoc-in [::oid oid :edn-limit] result)
                (fc/transact! env/app [[::oid oid]])
                )}))))}))

(defn add-new-nav-obj
  "fetches data for direct display, no tap summary"
  [state rid oid]
  (swap! state update-in [::oid oid] merge {::rid rid
                                            ::oid oid})

  (call! {:op :obj-describe
          :rid rid
          :oid oid}
    {:obj-summary
     (fn [{:keys [summary] :as msg}]
       (swap! state assoc-in [::oid oid :summary] (add-ts summary))
       ;; FIXME: don't rerender just yet?
       (fc/transact! env/app [[::oid oid]])
       (maybe-fetch-initial-fragment state (get-in @state [::oid oid])))}))

(defn add-first [prev head max]
  (into [head] (take (min max (count prev))) prev))

(defn add-last [prev tail max]
  (let [new (conj prev tail)
        c (count new)]
    (if (>= (count new) max)
      (subvec new (- c max) c)
      new)))

(defmutation request-fragment [{:keys [oid idx] :as params}]
  (action [{:keys [state] :as env}]

    (let [rid (get-in @state [::oid oid ::rid])]
      (swap! state update-in [::oid oid :fragment idx] merge {})

      (call! {:op :obj-request
              :rid rid
              :oid oid
              :start idx
              :num 1
              :request-op :fragment
              :key-limit 100
              :val-limit 100}
        {:obj-result
         (fn [{:keys [result] :as msg}]
           (swap! state update-in [::oid oid :fragment] merge result)
           (fc/transact! env/app [[::oid oid]])
           )}))))

(defmutation select-runtime [{:keys [rid] :as params}]
  (action [{:keys [state] :as env}]

    (call! {:op :request-tap-history :rid rid :num 100}
      {:tap-history
       (fn [{:keys [rid oids] :as msg}]
         (let [data @state]
           (doseq [oid oids
                   :when (not (get-in data [::oid oid :summary]))]
             (add-new-tap-obj state rid oid)))

         (swap! state
           (fn [data]
             (-> data
                 (assoc-in [::rid rid ::objects] (as-idents ::oid oids))
                 ))))})

    (let [[_ current] (get-in @state [::ui-model/page-inspect 1 ::runtime])]
      (when (not= rid current)
        (when current
          (cast! {:op :tap-unsubscribe :rid current}))
        (cast! {:op :tap-subscribe :rid rid}))


      ;; FIXME: this should fail somehow if runtime doesn't exist
      ;; but must account for runtime not being loaded yet

      (swap! state
        (fn [state]
          (-> state
              (assoc-in [::ui-model/page-inspect 1 ::runtime] [::rid rid])
              ;; FIXME: rid may still be empty when routing to this page
              ;; before :request-runtimes finished. :clj will always have id 1
              (update-in [::rid rid] merge {::rid rid})
              (update-in [::rid rid] dissoc ::object)
              (update-in [::rid rid] assoc ::nav-stack [])))))))

(defmutation unselect-object [{:keys [oid] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [rid] :as obj}
          (get-in @state [::oid oid])]

      (swap! state update-in [::rid rid] merge {::object nil
                                                ::nav-stack []}))))

(defmutation select-object [{:keys [oid] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [rid] :as obj}
          (get-in @state [::oid oid])]

      (maybe-fetch-initial-fragment state obj)

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

      (call! {:op :obj-request
              :rid rid
              :oid oid
              :request-op :nav
              :idx idx}

        ;; FIXME: maybe nav should return simple values, instead of ref to simple value
        {:obj-result
         (fn [msg]
           (js/console.log "nav request didn't return reference" msg))
         :obj-result-ref
         (fn [{:keys [ref-oid] :as msg}]
           (swap! state assoc-in [::rid rid ::object] [::oid ref-oid])
           (add-new-nav-obj state rid ref-oid))}))))

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
        (when-not (contains? obj :pprint)
          (call! {:op :obj-request
                  :rid rid
                  :oid oid
                  :request-op :pprint}
            {:obj-request-failed
             (fn [{:keys [e] :as msg}]
               (js/console.log "request-pprint failed" msg)
               (swap! state assoc-in [::oid oid :pprint] (str "Failed: " e))
               (fc/transact! env/app [[::oid oid]]))

             :obj-result
             (fn [{:keys [result] :as msg}]
               (js/console.log "request-pprint" msg)
               (swap! state assoc-in [::oid oid :pprint] result)
               (fc/transact! env/app [[::oid oid]]))}))


        ;; FIXME: repeated code
        :edn
        (when-not (contains? obj :edn)
          (call! {:op :obj-request
                  :rid rid
                  :oid oid
                  :request-op :edn}
            {:obj-request-failed
             (fn [{:keys [e] :as msg}]
               (js/console.log "request-edn failed" msg)
               (swap! state assoc-in [::oid oid :edn] (str "Failed: " e))
               (fc/transact! env/app [[::oid oid]]))

             :obj-result
             (fn [{:keys [result] :as msg}]
               (js/console.log "request-edn" msg)
               (swap! state assoc-in [::oid oid :edn] result)
               (fc/transact! env/app [[::oid oid]]))}))

        ;; don't need to do anything for :browse
        nil))))

(defmulti handle-tool-msg
  (fn [env msg] (:op msg))
  :default ::default)

(defmutation tool-msg-tx [params]
  (action [env]
    (handle-tool-msg env params)))


(defmethod handle-tool-msg ::default [_ msg]
  (js/console.warn "unhandled tool msg" msg))

(defmethod handle-tool-msg :welcome
  [{:keys [state] :as env} {:keys [tid]}]
  (swap! state assoc ::tid tid))

(defmethod handle-tool-msg :tap
  [{:keys [state]} {:keys [rid oid] :as msg}]
  (add-new-tap-obj state rid oid)
  (swap! state update-in [::rid rid ::objects] add-first [::oid oid] 100))

(declare Page)

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

(defmethod handle-tool-msg :runtime-not-found
  [{:keys [app state] :as env} {:keys [rid]}]
  (let [^goog history
        (-> (::fa/runtime-atom app)
            deref
            (::fa/shared-props)
            (::env/history))]
    (.setToken history "inspect")))

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
              {:keys [op mid] :as msg} (transit/read t (.-data e))]

          (js/console.log "message" msg)
          (if mid
            (let [handler (get-in @rpc-ref [mid op])]
              (if-not handler
                (js/console.log "received rpc reply without handler" msg)
                (handler msg)))

            ;; (js/console.log "tool-msg" msg)
            (fc/transact! env/app [(tool-msg-tx msg)])))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e)
        (connect-callback)
        (cast! {:op :request-runtimes})))

    (.addEventListener socket "close"
      (fn [e]
        (js/console.log "tool-close" e)
        ))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))

    svc))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;; RENDER ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  [this {:keys [oid obj-type entries]} fragment]
  (html/div {}
    (html/for [idx (range entries)
               :let [{:keys [val] :as entry} (get fragment idx)]]
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
            (html/div {:className "px-2 flex-1 truncate"} (render-edn-limit val)))))
      )))

(defmethod render-view :map
  [this {:keys [oid entries] :as summary} fragment]
  (html/div {:className "bg-white w-full"}
    (html/for [idx (range entries)
               :let [{:keys [key val] :as entry} (get fragment idx)
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
                   :className "border-b hover:bg-gray-200"
                   :onClick
                   (fn [e]
                     (fc/transact! this [(nav-object {:oid oid
                                                      :idx idx
                                                      :key key})]))}
          (html/div {:className "px-4 py-1 font-bold whitespace-no-wrap cursor-pointer truncate"}
            (render-edn-limit key))
          (html/div {:className "pl-4 pr-4 py-1 cursor-pointer truncate"}
            "↳ " (render-edn-limit val)))

        :else
        (html/div {:key idx
                   :className "flex py-1 border-b hover:bg-gray-200"
                   :onClick ;; FIXME: only add if nav is actually supported
                   (fn [e]
                     (fc/transact! this [(nav-object {:oid oid
                                                      :idx idx
                                                      :key key})]))}
          (html/div
            {:className "pl-4 pr-2 font-bold whitespace-no-wrap cursor-pointer truncate"
             :style {:flex "220px 0 0"}}
            (render-edn-limit key)
            #_(let [[limit-reached text] key
                    len (count text)]
                (if (> len 22)
                  (str (subs text 0 6) "..." (subs text (- len 13) len))
                  text)))
          (html/div
            {:className "pr-4 cursor-pointer truncate"}
            (render-edn-limit val)
            ))))))

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
        {:keys [ts ns line column label]} summary]

    (html/div {:className "font-mono border-b px-2 py-1 cursor-pointer hover:bg-gray-200"
               :onClick #(onSelect oid)}
      (html/div {:className "text-xs text-gray-500"}

        (str ts
             (when ns
               (str " - " ns
                    (when line
                      (str "@" line
                           (when column
                             (str ":" column))))))
             (when label
               (str " - " label))))
      (html/div {:className "truncate"}
        (render-edn-limit edn-limit)))
    ))

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
      (html/div {:className "flex-1"}) ;; fill up space
      (html/div {:className "text-right cursor-pointer font-bold px-2"
                 :onClick
                 (fn [e]
                   (fc/transact! this [(unselect-object {:oid oid})]))}

        ;; FIXME: find some nice icons
        "X"))))

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

  (let [select-fn
        (fn [oid]
          (fc/transact! this [(select-object {:rid rid
                                              :oid oid})]))

        scroll-ref
        (fc/get-state this :scroll-ref)

        full-tap?
        (and (empty? nav-stack)
             (nil? object))]

    (html/fragment
      (html/div {:className "bg-white font-mono font-bold px-4 border-b py-1 text-l"} "Tap History")
      (html/div {:className (str "bg-white font-mono overflow-y-auto border-b-4" (when full-tap? " flex-1"))
                 :style #js {:height "243px"}}
        (cond
          (nil? objects)
          (html/div {:className "p-4"} "Loading ...")

          (empty? objects)
          (html/div {:className "p-4"} "No taps yet.")

          :else
          (html/for [obj objects]
            (ui-object-list-item
              (fc/computed obj {:onSelect select-fn})))))

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
