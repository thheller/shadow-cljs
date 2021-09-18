(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.common :as common]
    [shadow.cljs.model :as m]))

(defn build-buttons [build-id build-worker-active]
  (if build-worker-active
    (<< [:div.font-bold.border-t.bg-gray-50
         [:button
          {:on-click {:e ::m/build-watch-stop! :build-id build-id}
           :class common/card-button-class
           :type "button"}
          "Stop"]
         [:button
          {:on-click {:e ::m/build-watch-compile! :build-id build-id}
           :class common/card-button-class
           :type "button"}
          "Force compile"]])

    (<< [:div.font-bold.border-t.bg-gray-50
         [:button
          {:on-click {:e ::m/build-watch-start! :build-id build-id}
           :class common/card-button-class
           :type "button"}
          "Watch"]
         [:button
          {:on-click {:e ::m/build-compile! :build-id build-id}
           :class common/card-button-class
           :type "button"}
          "Compile"]
         [:button
          {:on-click {:e ::m/build-release! :build-id build-id}
           :class common/card-button-class
           :type "button"}
          "Release"]])))


(defc build-card [ident]
  (bind {::m/keys [build-status build-id build-target build-warnings-count build-worker-active] :as data}
    (sg/query-ident ident
      [::m/build-id
       ::m/build-target
       ::m/build-worker-active
       ::m/build-warnings-count
       ::m/build-status
       ::m/build-config-raw]))

  (render
    (let [{:keys [status]} build-status]
      (<< [:div.bg-white.overflow-hidden.shadow
           [:a {:ui/href (str "/build/" (name build-id))}
            [:div.p-4
             [:div.flex.items-center
              [:div.flex-shrink-0
               (case status
                 :compiling
                 common/icon-compiling

                 :completed
                 (if (zero? build-warnings-count)
                   common/icon-warnings
                   common/icon-completed)

                 :failed
                 common/icon-failed

                 :inactive
                 common/icon-inactive

                 :pending
                 common/icon-pending

                 ;default
                 common/icon-inactive)]

              [:div.ml-5.w-0.flex-1
               [:dl
                [:dt.text-lg.font-medium.text-gray-900 (name build-id)]
                [:dd
                 [:span.text-sm.font-medium.text-gray-500.truncate (name build-target)]]]]]]]

           (build-buttons build-id build-worker-active)]))))

(defc ui-builds-page []
  (bind {::m/keys [builds]}
    (sg/query-root [::m/builds]))

  (render
    (<< [:div.flex-1.overflow-auto.py-2.sm:px-2
         [:div.flex.flex-col
          [:div.grid.grid-cols-1.gap-2
           (sg/keyed-seq builds identity build-card)]]])))

(defc ui-build-overview [build-ident]
  (bind {::m/keys [build-sources-sorted] :as data}
    (sg/query-ident build-ident
      [::m/build-sources-sorted]))

  (bind state-ref
    (atom {:selected nil}))

  (bind selected
    (sg/watch state-ref [:selected]))

  (bind id->src
    (into {} (map (juxt :resource-id identity)) build-sources-sorted))

  (bind id->idx
    (reduce-kv
      (fn [m idx {:keys [resource-id] :as v}]
        (assoc m resource-id idx))
      {}
      build-sources-sorted))

  (event ::highlight [env {:keys [resource-id]}]
    (swap! state-ref assoc :selected resource-id))

  (bind render-source-entry
    (fn [{:keys [resource-id resource-name] :as item}]
      (let [selected? (= resource-id selected)]
        (<< [:div.text-xs
             {:class (when selected? "font-bold")
              :on-mouseenter {:e ::highlight :resource-id resource-id}}
             resource-name]))))

  (render
    (<< [:div.p-2
         [:div.py-2.text-xl (count build-sources-sorted) " Namespaces used in build"]
         [:div.flex
          [:div.flex-1 "left"]
          [:div
           (sg/simple-seq build-sources-sorted render-source-entry)]
          [:div.flex-1 "right"]]])))