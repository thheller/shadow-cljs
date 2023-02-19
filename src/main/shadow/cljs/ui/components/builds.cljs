(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.css :refer (css)]
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.common :as common]
    [shadow.cljs.model :as m]))

(defn build-buttons [build-id build-worker-active]
  (<< [:div {:class (css :font-bold :border-t :c-container-1l :border-gray-200)}
       (if build-worker-active
         (<< [:button
              {:on-click {:e ::m/build-watch-stop! :build-id build-id}
               :class common/card-button-class
               :type "button"}
              "Stop"]
             [:button
              {:on-click {:e ::m/build-watch-compile! :build-id build-id}
               :class common/card-button-class
               :type "button"}
              "Force compile"])

         (<< [:button
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
              "Release"]))]))


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
      (<< [:div {:class (css :bg-white :overflow-hidden :shadow)}
           [:a {:ui/href (str "/build/" (name build-id))}
            [:div {:class (css :p-4)}
             [:div {:class (css :flex :items-center)}
              [:div {:class (css {:flex-shrink 0})}
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

              [:div {:class (css :ml-5 :w-0 :flex-1)}
               [:dl
                [:dt {:class (css :text-lg :font-medium :c-text-1d)} (name build-id)]
                [:dd
                 [:span {:class (css :text-sm :font-medium :c-text-1l :truncate)} (name build-target)]]]]]]]

           (build-buttons build-id build-worker-active)]))))

(defc ui-builds-page []
  (bind {::m/keys [builds]}
    (sg/query-root [::m/builds]))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto :py-2 [:sm :px-2])}
         [:div {:class (css :flex :flex-col)}
          [:div {:class (css :grid :grid-cols-1 :gap-2)}
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
        (<< [:div
             {:class
              (if selected?
                (css :text-xs :font-bold)
                (css :text-xs))
              :on-mouseenter {:e ::highlight :resource-id resource-id}}
             resource-name]))))

  (render
    (<< [:div {:class (css :p-2)}
         [:div {:class (css :py-2 :text-xl)} (count build-sources-sorted) " Namespaces used in build"]
         [:div {:class (css :flex)}
          [:div {:class (css :flex-1)} "left"]
          [:div
           (sg/simple-seq build-sources-sorted render-source-entry)]
          [:div {:class (css :flex-1)} "right"]]])))