(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]))

(defn build-buttons [build-id build-worker-active]
  (let [class-button
        "font-medium border-r py-3 px-5 bg-gray-50 hover:bg-gray-300 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-500"]
    (if build-worker-active
      (<< [:div.font-bold.border-t.bg-gray-50
           [:button
            {:on-click {:e ::m/build-watch-stop! :build-id build-id}
             :class class-button
             :type "button"}
            "Stop"]
           [:button
            {:on-click {:e ::m/build-watch-compile! :build-id build-id}
             :class class-button
             :type "button"}
            "Force compile"]])

      (<< [:div.font-bold.border-t.bg-gray-50
           [:button
            {:on-click {:e ::m/build-watch-start! :build-id build-id}
             :class class-button
             :type "button"}
            "Watch"]
           [:button
            {:on-click {:e ::m/build-compile! :build-id build-id}
             :class class-button
             :type "button"}
            "Compile"]
           [:button
            {:on-click {:e ::m/build-release! :build-id build-id}
             :class class-button
             :type "button"}
            "Release"]]))))

(def icon-compiling
  (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-blue-400.rounded-full]]))

(def icon-warnings
  (<< [:span.h-6.w-6.bg-green-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-green-400.rounded-full]]))

(def icon-completed
  (<< [:span.h-6.w-6.bg-yellow-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-yellow-400.rounded-full]]))

(def icon-failed
  (<< [:span.h-6.w-6.bg-red-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-red-400.rounded-full]]))

(def icon-inactive
  (<< [:span.h-6.w-6.bg-gray-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-gray-400.rounded-full]]))

(def icon-pending
  (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-blue-400.rounded-full]]))

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
           [:a {:href (str "/build/" (name build-id))}
            [:div.p-4
             [:div.flex.items-center
              [:div.flex-shrink-0
               (case status
                 :compiling
                 icon-compiling

                 :completed
                 (if (zero? build-warnings-count)
                   icon-warnings
                   icon-completed)

                 :failed
                 icon-failed

                 :inactive
                 icon-inactive

                 :pending
                 icon-pending

                 ;default
                 icon-inactive)]

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