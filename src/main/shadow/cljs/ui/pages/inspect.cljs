(ns shadow.cljs.ui.pages.inspect
  (:require
    [clojure.string :as str]
    [fipp.edn :refer (pprint)]
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
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.cljs.ui.util :as util]
    ["codemirror" :as cm]
    ["codemirror/mode/clojure/clojure"]
    ["codemirror/mode/javascript/javascript"]
    ["parinfer-codemirror" :as par-cm])

  (:import [goog.i18n DateTimeFormat]))

(def date-format (DateTimeFormat. "yyyy-MM-dd HH:mm:ss"))
(def time-format (DateTimeFormat. "HH:mm:ss"))

(defn refresh-ui [idents]
  ;; (js/console.log "refresh-queued" idents)
  (fa/schedule-render! env/app {:only-refresh idents})
  ;; (fc/transact! env/app [] {:refresh idents})
  )

(defonce tool-ref (atom nil))
(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))

(defn reduce-> [init fn vals]
  (reduce fn init vals))

(defn cast! [msg]
  ;; (js/console.log "ws-out" msg)
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
  (when (contains? (:supports summary) :get-value)
    (call! {:op :obj-request
            :rid rid
            :oid oid
            :request-op :get-value}
      {:obj-result
       (fn [{:keys [result] :as msg}]
         (swap! state assoc-in [::oid oid :value] result)
         (refresh-ui [[::oid oid]]))}))

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
             (refresh-ui [[::oid oid]]))})))))

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
                   :limit 150}

             {:obj-result
              (fn [{:keys [result] :as msg}]
                (swap! state assoc-in [::oid oid :edn-limit] result)
                (refresh-ui [[::oid oid]])
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
       (maybe-fetch-initial-fragment state (get-in @state [::oid oid]))
       (refresh-ui [[::oid oid]
                    [::tap-page rid]])
       )}))

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
           (refresh-ui [[::oid oid]])
           )}))))

(defn get-state-ref []
  (::fa/state-atom env/app))

(defn ensure-runtime-init [rid pending-tx callback]
  (let [state-ref (get-state-ref)]
    (if (get-in @state-ref [::rid rid ::supported-ops])
      (callback)

      (do (swap! state-ref update-in [::rid rid] merge {::rid rid})
          (fc/transact! env/app pending-tx)
          (call! {:op :request-supported-ops :rid rid}
            {:runtime-not-found
             (fn [msg]
               ;; FIXME: redirect somewhere neutral
               (js/console.warn "runtime-not-found" rid))

             :supported-ops
             (fn [{:keys [ops] :as msg}]
               (swap! state-ref assoc-in [::rid rid ::supported-ops] ops)
               (callback)
               (refresh-ui [[::rid rid]])
               )})))))

(defmutation select-runtime-page [{:keys [rid] :as params}]
  (action [{:keys [state] :as env}]
    (swap! state update-in [::runtime-page rid] merge {::rid rid ::runtime [::rid rid]})))

(defmutation select-runtime-tap-page [{:keys [rid] :as params}]
  (action [{:keys [state] :as env}]
    (swap! state update-in [::tap-page rid] merge {::rid rid ::runtime [::rid rid]})

    ;; FIXME: allow having multiple active?
    (let [previous-rid (::active-rid @state)]

      (swap! state
        (fn [state]
          (-> state
              (assoc ::active-rid rid)
              (update-in [::tap-page rid] dissoc ::object)
              (update-in [::tap-page rid] assoc ::nav-stack []))))

      (when (contains? (get-in @state [::rid rid ::supported-ops]) :request-tap-history)
        (when (not= rid previous-rid)
          (when previous-rid ;; may be nil
            (cast! {:op :tap-unsubscribe :rid previous-rid}))
          (cast! {:op :tap-subscribe :rid rid}))

        (call! {:op :request-tap-history :rid rid :num 100}
          {:tap-history
           (fn [{:keys [rid oids] :as msg}]
             (let [data @state]
               (doseq [oid oids
                       :when (not (get-in data [::oid oid :summary]))]
                 (add-new-tap-obj state rid oid)))

             (swap! state assoc-in [::tap-page rid ::objects] (as-idents ::oid oids))
             (refresh-ui [[::tap-page rid]])
             )}))
      )))

(defmutation unselect-object [{:keys [oid] :as params}]
  (action [{:keys [state] :as env}]

    (let [{::keys [rid] :as obj}
          (get-in @state [::oid oid])]

      (swap! state update-in [::tap-page rid] merge {::object nil
                                                     ::nav-stack []}))))

(defmutation select-tap-object [{:keys [rid oid] :as params}]
  (action [{:keys [state] :as env}]

    (let [obj (get-in @state [::oid oid])]
      (maybe-fetch-initial-fragment state obj)

      (swap! state update-in [::tap-page rid] merge
        {::object [::oid oid]
         ::nav-stack []}))

    ;; (js/console.log "select-object" params)
    ))

(defmutation nav-object [{:keys [oid idx] :as params}]
  (action [{:keys [state] :as env}]
    (let [{::keys [rid]} (get-in @state [::oid oid])]

      ;; FIXME: should show some loading indicator and prevent other nav
      ;; otherwise they'll race and cause weird UI updates

      (call! {:op :obj-request
              :rid rid
              :oid oid
              :request-op :nav
              :idx idx}

        ;; FIXME: maybe nav should return simple values, instead of ref to simple value
        {:obj-result
         (fn [{:keys [result] :as msg}]
           ;; FIXME: for now this is just for nil
           (js/console.log "nav request didn't return reference" msg))
         :obj-result-ref
         (fn [{:keys [ref-oid] :as msg}]
           (swap! state
             (fn [state]
               (-> state
                   (update-in [::tap-page rid ::nav-stack] conj params)
                   (assoc-in [::tap-page rid ::object] [::oid ref-oid]))))
           (refresh-ui [[::tap-page rid]
                        [::oid ref-oid]])
           (add-new-nav-obj state rid ref-oid))}))))

(defmutation nav-stack-jump [{:keys [rid oid idx] :as params}]
  (action [{:keys [state] :as env}]

    (swap! state
      (fn [state]
        (-> state
            (assoc-in [::tap-page rid ::object] [::oid oid])
            (update-in [::tap-page rid ::nav-stack] subvec 0 idx))))
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
               (refresh-ui [[::oid oid]]))

             :obj-result
             (fn [{:keys [result] :as msg}]
               (swap! state assoc-in [::oid oid :pprint] result)
               (refresh-ui [[::oid oid]]))}))


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
               (refresh-ui [[::oid oid]]))

             :obj-result
             (fn [{:keys [result] :as msg}]
               (swap! state assoc-in [::oid oid :edn] result)
               (refresh-ui [[::oid oid]])
               )}))

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
  (swap! state update-in [::tap-page rid ::objects] add-first [::oid oid] 100))

(declare Page)

(defmethod handle-tool-msg :runtimes
  [{:keys [state]} {:keys [runtimes]}]
  (let [db-data
        (->> runtimes
             (map (fn [{:keys [rid] :as runtime-info}]
                    {::rid rid
                     ::runtime-info runtime-info}))
             (into []))]

    (doseq [{:keys [rid]} runtimes]
      (call!
        {:op :request-supported-ops
         :rid rid}
        {:supported-ops
         (fn [{:keys [ops] :as msg}]
           (swap! state assoc-in [::rid rid ::supported-ops] ops)
           (refresh-ui [[::rid rid]
                        [::ui-model/page-inspect 1]
                        ::supported-ops]))}))

    (swap! state fam/merge-component Page {::runtimes db-data})))

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
  (swap! state assoc-in [::rid rid] {::rid rid
                                     ::runtime-info runtime-info})
  (ensure-runtime-init rid []
    (fn []
      (swap! state update-runtimes))))

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

          ;; (js/console.log "message" msg)
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
        ))

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
  (html/div {:className "p-4"}
    (html/div {:className "py-1 text-xl font-bold"} "Object does not support Browse view.")
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

(defn render-seq
  [this {:keys [oid entries]} fragment]
  (html/div {}
    (html/for [idx (range entries)
               :let [{:keys [val] :as entry} (get fragment idx)]]
      (html/div {:key idx :className "border-b"}

        (html/div {:className "flex"
                   :onClick
                   (fn [e]
                     (fc/transact! this [(nav-object {:oid oid
                                                      :idx idx})]))}
          (html/div {:className "pl-4 px-2 border-r text-right"
                     :style {:width "60px"}} idx)

          (cond
            (nil? entry)
            (html/div
              {:className "px-2"
               :onMouseEnter
               (fn [e]
                 (fc/transact! this [(request-fragment {:oid oid
                                                        :idx idx})]))}
              "not available, fetch")

            :else
            (html/div {:className "px-2 flex-1 truncate"} (render-edn-limit val))
            ))))))

(defmethod render-view :vec
  [this summary fragment]
  (render-seq this summary fragment))

(defmethod render-view :set
  [this summary fragment]
  (render-seq this summary fragment))

(defmethod render-view :list
  [this summary fragment]
  (render-seq this summary fragment))

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

;; {:className "pl-4 pr-2 font-bold whitespace-no-wrap truncate"}
(defstyled map-key :div [env]
  {:font-weight "bold"
   :padding "0 .25rem"
   :white-space "nowrap"
   :overflow "hidden"
   :text-overflow "ellipsis"
   :background-color "#fafafa"
   "&:hover"
   {:background-color "#eee"}})

;; {:className "pr-4 truncate"}
(defstyled map-val :div [env]
  {:white-space "nowrap"
   :overflow "hidden"
   :text-overflow "ellipsis"})

(defmethod render-view :map
  [this {:keys [oid entries] :as summary} fragment]
  (map-root {:class "font-mono"}
    (html/for [idx (range entries)
               :let [{:keys [key val] :as entry} (get fragment idx)
                     [key-limit-reached key-limit-text] key]]
      (cond
        (nil? entry)
        (map-span
          {:key idx :className "border"
           :onMouseEnter
           (fn [e]
             (fc/transact! this [(request-fragment {:oid oid
                                                    :idx idx})]))}
          "not available, fetch")

        ;; started loading
        (empty? entry)
        (map-span
          {:key idx :className "border"} "...")

        ;; long key
        #_#_(or key-limit-reached
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
        (map-entry {:key idx
                    :onClick
                    (fn [e]
                      (fc/transact! this [(nav-object {:oid oid
                                                       :idx idx
                                                       :key key})]))}
          (map-key
            (render-edn-limit key))
          (map-val
            (render-edn-limit val)))
        ))))

(defsc Runtime [this {::keys [rid] :as props}]
  {:ident
   (fn []
     [::rid rid])

   :query
   (fn []
     [::rid
      ::runtime-info
      ::supported-ops])}

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

(defsc ObjectHeader [this {::keys [oid] :as props}]
  (let [{:keys [summary]} props
        {:keys [obj-type entries]} summary]
    (html/div {:className "flex bg-white py-1 px-2 font-mono border-b-2 text-l"}
      #_(html/div {:className "pr-2 py-2 font-bold"} (str data-type))
      (html/div {:className "px-2 font-bold"} obj-type)
      (when entries
        (html/div {:className "px-2 font-bold"} (str entries " Entries")))
      (html/div {:className "flex-1"}) ;; fill up space
      (html/div {:className "text-right cursor-pointer font-bold px-2"
                 :onClick
                 (fn [e]
                   (fc/transact! this [(unselect-object {:oid oid})]))}

        ;; FIXME: find some nice icons
        "X"))))

(def ui-object-header (fc/factory ObjectHeader {}))

(defsc ObjectFooter [this {::keys [oid] :as props}]
  (let [{::keys [display-type] :or {display-type :browse}} props]
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

(defsc ObjectDetail [this {::keys [oid] :as props}]
  (let [{::keys [display-type]
         :keys [summary fragment edn-limit]
         :or {display-type :browse}}
        props]

    (if (nil? summary)
      (html/div {:className "flex-1 bg-white font-mono p-4"} "Loading ...")
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

        :browse
        (html/div {:className "flex-1 overflow-auto font-mono bg-white"}
          (render-view
            this
            (assoc summary
              :value (:value props)
              :oid oid
              :edn-limit edn-limit)
            fragment))))
    ))

(def ui-object-detail (fc/factory ObjectDetail {}))

(defsc ObjectInspect [this {::keys [oid] :as props}]
  {:ident
   (fn []
     [::oid oid])
   :query
   (fn []
     [::oid
      ::display-type
      :edn-limit
      :value
      :summary
      :fragment
      :edn
      :pprint])}

  (html/fragment
    (ui-object-header props)
    (ui-object-detail props)
    (ui-object-footer props)))

(def ui-object-inspect (fc/factory ObjectInspect {:keyfn ::oid}))

(defn do-eval [comp eval-op]
  (let [{::keys [^js editor term]} (util/get-local! comp)
        {::keys [rid]} (fc/props comp)
        state (::fa/state-atom env/app)

        ;; FIXME: might be nil
        oid (get-in @state [::tap-page rid ::object 1])

        text
        (str/trim (.getValue editor))

        ;; extremely hacky way to get access runtime refs
        wrap
        (str "(let [$ref (shadow.remote.runtime.eval-support/get-ref " (pr-str oid) ")\n"
             "      $o (:obj $ref)\n"
             "      $d (-> $ref :desc :data)]\n"
             "?CODE?\n"
             "\n)")]

    (when (seq text)

      (.setValue editor "")

      (call!
        {:op eval-op
         :rid rid
         :code text
         :wrap wrap ;; FIXME: don't do this for :eval-js
         ;; this is ugly, not sure if text is much better though?
         #_`(let [~'$ref (shadow.remote.runtime.eval-support/get-ref ~oid)
                  ~'$o (:obj ~'$ref)
                  ~'$d (~'-> ~'$ref :desc :data)]
              ~'?CODE?)}

        {:unknown-op
         (fn [msg]
           (js/console.log "eval-not-supported" msg))

         :eval-error
         (fn [msg]
           (js/console.log "eval-error" msg))

         :eval-result-ref
         (fn [{:keys [ref-oid] :as msg}]
           (swap! state update-in [::tap-page rid ::nav-stack] conj {:oid oid :key [false text]})
           (swap! state assoc-in [::tap-page rid ::object] [::oid ref-oid])
           (add-new-nav-obj state rid ref-oid))

         :eval-result
         (fn [msg]
           (js/console.log "eval-result" msg))}))))

(defn attach-codemirror-clj [comp cm-input eval-op]
  ;; (js/console.log ::attach-codemirror cm-input comp)
  (if-not cm-input
    (let [{::keys [editor]} (util/get-local! comp)]
      (when editor
        (.toTextArea editor))
      (util/swap-local! comp dissoc ::editor))

    (let [editor
          (cm/fromTextArea
            cm-input
            #js {:lineNumbers true
                 :mode "clojure"
                 :theme "github"
                 :autofocus true
                 :matchBrackets true})]

      (.setOption editor "extraKeys"
        #js {"Ctrl-Enter" #(do-eval comp eval-op)
             "Shift-Enter" #(do-eval comp eval-op)})

      (par-cm/init editor)
      (util/swap-local! comp assoc ::editor editor))))

(defn attach-codemirror-js [comp cm-input eval-op]
  ;; (js/console.log ::attach-codemirror cm-input comp)
  (if-not cm-input
    (let [{::keys [editor]} (util/get-local! comp)]
      (when editor
        (.toTextArea editor))
      (util/swap-local! comp dissoc ::editor))

    (let [editor
          (cm/fromTextArea
            cm-input
            #js {:lineNumbers true
                 :mode "javascript"
                 :theme "github"
                 :autofocus true
                 :matchBrackets true})]

      (.setOption editor "extraKeys"
        #js {"Ctrl-Enter" #(do-eval comp eval-op)
             "Shift-Enter" #(do-eval comp eval-op)})

      (util/swap-local! comp assoc ::editor editor))))

(defsc RuntimeEval [this {::keys [rid supported-ops] :as props}]
  {:ident
   (fn []
     [::rid rid])
   :query
   (fn []
     [::rid
      ::supported-ops])}

  (when (or (contains? supported-ops :eval-clj)
            (contains? supported-ops :eval-cljs)
            (contains? supported-ops :eval-js))

    (html/div {:className "bg-white font-mono flex flex-col"}
      (html/div {:className "flex font-bold px-4 border-b border-t-2 py-1 text-l"}
        (html/div {:className "flex-1"} "Runtime Eval (use $o for current obj, ctrl+enter for eval)")
        (html/div "Language: ")
        (html/div {}
          (html/select {}
            (html/option "Clojure")
            (html/option "ClojureScript")
            (html/option "JS"))))
      (html/div {:style {:height "120px"}}
        #_(html/input {:ref (util/comp-fn this ::editor-js attach-codemirror-js :eval-js)})
        (html/input {:ref (util/comp-fn this ::editor-clj attach-codemirror-clj :eval-cljs)})))))

(def ui-runtime-eval (fc/factory RuntimeEval {}))

(defsc RuntimeTapPage
  [this {::keys [rid] :as props}]
  {:ident
   (fn []
     [::tap-page rid])

   :query
   (fn []
     [::rid
      {::runtime (fc/get-query RuntimeEval)}
      {::objects (fc/get-query ObjectListItem)}
      {::object (fc/get-query ObjectInspect)}
      ::nav-stack])}

  (let [{::keys [runtime objects object nav-stack]}
        props

        select-fn
        (fn [oid]
          (fc/transact! this [(select-tap-object {:rid rid :oid oid})]))

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
        (ui-object-inspect object))

      #_ (ui-runtime-eval runtime))))

(def ui-runtime-tap-page (fc/factory RuntimeTapPage {}))

(defsc RuntimePage
  [this {::keys [rid] :as props}]
  {:ident
   (fn []
     [::runtime-page rid])

   :query
   (fn []
     [::rid
      {::runtime (fc/get-query Runtime)}])}

  (let [{::keys [runtime]} props]
    (html/div
      (html/h1 "Runtime Overview"))))

(def ui-runtime-page (fc/factory RuntimePage {}))

(defsc RuntimeSelect [this {::keys [rid] :as props}]
  {:ident
   (fn []
     [::rid rid])

   :query
   (fn []
     [::rid
      ::runtime-info
      ::supported-ops])}

  (let [{::keys [supported-ops runtime-info]} props]

    (html/div
      {:key rid
       :className "p-2 border font-mono"}
      (html/div
        (pr-str runtime-info))

      (html/div {:className "py-8"}
        (when (contains? supported-ops :tap-subscribe)
          (html/a {:className "p-4 text-l rounded bg-blue-300"
                   :href (str "/inspect/" rid "/tap")} "Tap"))

        ))))

(def ui-runtime-select (fc/factory RuntimeSelect {:keyfn ::rid}))

(defsc Page [this {::keys [runtimes] :as props}]
  {:ident
   (fn []
     [::ui-model/page-inspect 1])

   :query
   (fn []
     [{::runtimes (fc/get-query RuntimeSelect)}])

   :initial-state
   (fn [p]
     {})}

  (html/div {:className "bg-white p-2 mb-2"}
    (html/div {:className "text-xl my-2"} "Runtimes")
    (html/for [runtime runtimes]
      (ui-runtime-select runtime)
      )))

(def ui-page (fc/factory Page {}))

(defn init [])

(routing/register ::ui-model/root-router ::ui-model/page-inspect
  {:class Page
   :factory ui-page})

(routing/register ::ui-model/root-router ::runtime-page
  {:class RuntimePage
   :factory ui-runtime-page})

(routing/register ::ui-model/root-router ::tap-page
  {:class RuntimeTapPage
   :factory ui-runtime-tap-page})

(defn route [tokens]
  (if-not @tool-ref
    ;; wait for websocket connect first, then do actual route
    (do (fc/transact! env/app
          [(routing/set-route
             {:router ::ui-model/root-router
              :ident [::ui-model/page-loading 1]})
           ::ui-model/root-router])
        (start-browser
          (fn []
            (cast! {:op :request-runtimes})
            (route tokens))))

    (if (empty? tokens)
      (fc/transact! env/app
        [(routing/set-route
           {:router ::ui-model/root-router
            :ident [::ui-model/page-inspect 1]})
         ::ui-model/root-router])

      (let [[rid view & more] tokens
            rid (js/parseInt rid)]

        (ensure-runtime-init rid
          [(routing/set-route
             {:router ::ui-model/root-router
              :ident [::ui-model/page-loading 1]})
           ::ui-model/root-router]
          (fn []
            (cond
              (nil? view)
              (fc/transact! env/app
                [(select-runtime-page {:rid rid})
                 (routing/set-route {:router ::ui-model/root-router
                                     :ident [::runtime-page rid]})
                 ::ui-model/root-router])

              (= "tap" view)
              (fc/transact! env/app
                [(select-runtime-tap-page {:rid rid})
                 (routing/set-route {:router ::ui-model/root-router
                                     :ident [::tap-page rid]})
                 ::ui-model/root-router])
              )))))))
