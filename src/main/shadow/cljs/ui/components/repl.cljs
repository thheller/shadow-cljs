(ns shadow.cljs.ui.components.repl
  (:require
    [shadow.cljs :as-alias m]
    [shadow.cljs.ui.db.relay-ws :as relay-ws]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.events :as ev]
    [shadow.grove.keyboard :as keyboard]
    [shadow.grove.kv :as kv]
    [shadow.cljs.ui.components.inspect :as inspect]
    ))


(defn ui-repl-crumb [stack-item panel-idx active?]
  (<< [:div {:class (css :inline-block :border-r :p-2 :cursor-pointer :whitespace-nowrap)
             :style/font-weight (if active? "600" "400")
             :on-click {:e ::m/inspect-set-current! :idx panel-idx}}
       "REPL"]))

(defn obj-load-preview
  {::ev/handle ::obj-load-preview}
  [env {:keys [call-result] :as msg}]
  (case (:op call-result)
    :obj-result
    (update-in env [::m/object (:oid call-result)] merge
      {:obj-preview (:result call-result)
       :oid (:oid call-result)
       :runtime-id (:from call-result)})))

(defn load-object [env {:keys [from ex-oid ref-oid]}]
  (relay-ws/cast!
    (::sg/runtime-ref env)
    {:op :obj-describe
     :to from
     :oid (or ex-oid ref-oid)}))

(defc ui-repl-result [{:keys [result] :as entry}]
  (bind runtime
    (sg/kv-lookup ::m/runtime (:from result)))

  (bind object
    (sg/kv-lookup ::m/object (:ref-oid result)))

  (effect :mount [env]
    (when (or (not object) (not (:summary object)))
      (load-object env result)))

  (render
    (if-not object
      "..."
      (<< [:div {:class (css :truncate :font-mono :p-1 :cursor-pointer)
                 :on-click {:e ::m/inspect-object! :oid (:oid object)}}
           (inspect/render-edn-limit (:preview (:summary object)))]))))

(defc ui-repl-error [{:keys [result] :as entry}]
  (bind runtime
    (sg/kv-lookup ::m/runtime (:from result)))

  (bind object
    (sg/kv-lookup ::m/object (:ex-oid result)))

  (effect :mount [env]
    (when (or (not object) (not (:summary object)))
      (load-object env result)))

  (render
    (if-not object
      "..."
      (<< [:div {:class (css :truncate :font-mono :p-1 :cursor-pointer)
                 :on-click {:e ::m/inspect-object! :oid (:oid object)}}
           (inspect/render-edn-limit (:preview (:summary object)))]))))

(defc ui-repl-entry [entry-id]
  (bind {:keys [result code target target-op target-ns] :as entry}
    (sg/kv-lookup ::m/repl-history entry-id))

  (render
    (<< [:div {:class (css :border-b-4 :flex :font-mono)
               #_#_:style/color (when (:is-error object) "red")}
         [:div {:class (css :p-1)}
          (cond
            (nil? result) "⏳"
            (= :eval-result-ref (:op result)) "✅"
            (= :eval-runtime-error (:op result)) "\uD83E\uDD2F"
            (= :eval-compile-error (:op result)) "\uD83E\uDD22"
            (= :client-not-found (:op result)) "\uD83E\uDEA6"
            :else "?")]


         [:div {:class (css :flex-1 :border-l)}
          (if (= :client-not-found (:op result))
            (<< [:div {:class (css :text-xs :p-1 :border-b :text-red-500)} (str "Runtime: #" target " not found. Eval could not complete.")])
            (<< [:div {:class (css :text-xs :p-1 :border-b)} (str "Runtime: #" target " Namespace: " target-ns)]))
          [:div {:class (css :truncate :border-b :p-1)} (or code "via Inspect")]
          (case (:op result)
            :eval-result-ref (ui-repl-result entry)
            :eval-runtime-error (ui-repl-error entry)
            :client-not-found nil
            "...")]])))

(defn ?runtime-options [env]
  (->> (::m/runtime env)
       (vals)
       (remove :disconnected)
       (filter (fn [{:keys [supported-ops] :as runtime}]
                 (or (contains? supported-ops :clj-eval)
                     (contains? supported-ops :cljs-eval))))
       (map (fn [{:keys [supported-ops] :as runtime}]
              [(:runtime-id runtime)

               (str "#" (:runtime-id runtime)
                    (cond
                      (contains? supported-ops :clj-eval)
                      " - CLJ"
                      (contains? supported-ops :cljs-eval)
                      (str " - CLJS (" (get-in runtime [:runtime-info :build-id]) ")")))
               ]))
       (into [[nil "Select Runtime ..."]])))

;; FIXME: make actually reusable select element
(defc select [change-ev val options]
  (bind dom-ref (sg/ref))

  ;; doing this dance since HTML select option .value is only ever a string
  ;; I'd like to preserve the actual value received via options
  ;; that does require a bit of manual index management, which is fine
  (effect :auto [env]
    (loop [idx (dec (count options))]
      (let [[opt-val label] (nth options idx)]
        (if (= opt-val val)
          (set! @dom-ref -selectedIndex idx)
          (when-not (zero? idx)
            (recur (dec idx)))))))

  (event ::change! [env ev e]
    (let [v-idx (.-selectedIndex @dom-ref)
          v (first (nth options v-idx))]
      (sg/run-tx env (assoc change-ev :value v :value-idx v-idx))))

  (render
    (<< [:select
         {:dom/ref dom-ref
          :on-change {:e ::change!}
          :class (css :py-1 :px-2 :border-l :border-r {:width "200px"})}
         (sg/simple-seq options
           (fn [[val label]]
             (<< [:option label])))])))

(defc repl-input [stream-id]
  (bind dom-ref (sg/ref))

  (event ::keyboard/ctrl+enter [env _ e]
    (.preventDefault e)
    (let [val (.-value @dom-ref)]
      (set! @dom-ref -value "")

      (relay-ws/cast!
        (::sg/runtime-ref env)
        {:op ::m/repl-stream-input!
         :to 1
         :stream-id stream-id
         :code val})))

  ;; FIXME: actually implement some sort of history
  (event ::keyboard/arrowup [env _ e]
    (let [el @dom-ref]
      (when (= 0 (.-selectionStart el) (.-selectionEnd el))
        (.preventDefault e)
        (js/console.log "history up")
        )))

  (event ::keyboard/arrowdown [env _ e]
    (let [el @dom-ref]
      (when (= (count (.-value el)) (.-selectionStart el) (.-selectionEnd el))
        (.preventDefault e)
        (js/console.log "history down")
        )))


  (render
    (<< [:textarea
         {::keyboard/listen true
          :dom/ref dom-ref
          :type "text"
          :class (css :block :font-mono :w-full :p-2 {:height "84px"})
          :placeholder "REPL Input ... ctrl+enter to eval"
          :name "code"}])))

(defc ui-repl-controls []
  (bind stream-id
    ;; FIXME: figure out ui to select stream if multiple exist
    :default)

  (bind stream
    (sg/kv-lookup ::m/repl-stream stream-id))

  (bind runtime-options
    (sg/query ?runtime-options))

  (render
    (<< [:div {:class (css :border-b :text-sm :flex)}
         [:div {:class (css :py-1 :px-2 :font-semibold)}
          " Runtime: "]
         [:div
          (select
            {:e ::m/repl-select-runtime!
             :stream-id stream-id}
            (:target stream)
            runtime-options)]

         [:div {:class (css :py-1 :px-2 :font-semibold)}
          " Current Namespace: "]
         [:div {:class (css :py-1)}
          (str (:target-ns stream))]]

      ;; FIXME: trying to get by without codemirror
      ;; input is supposed to come from editor, this is just for "emergencies"
      ;; does making it extra bad help push users to use editor instead?

      [:div {:class (css :border-b-2)}
       (repl-input stream-id)

       ])))

(defc ui-repl-panel [item panel-idx active?]
  (bind repl-history
    (sg/query
      (fn [env]
        (->> (::m/repl-history env)
             (vals)
             (sort-by :id)
             (reverse)
             (mapv :id)))))

  (render
    (let [$container (css :flex-1 :overflow-auto :bg-white)]

      (<< [:div {:class $container}
           (ui-repl-controls)

           ;; FIXME: vlist
           [:div {:class (css :overflow-hidden)}
            (sg/simple-seq repl-history ui-repl-entry)]]
        ))))


(defmethod inspect/render-crumb :repl-panel [item idx active?]
  (ui-repl-crumb item idx active?))

(defmethod inspect/render-panel :repl-panel [item idx active?]
  (ui-repl-panel item idx active?))

(defn ui-page []
  ;; abusing inspect for this for now
  (inspect/ui-page))

