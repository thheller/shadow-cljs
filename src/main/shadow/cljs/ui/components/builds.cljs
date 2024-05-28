(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.css :refer (css)]
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.common :as common]
    [shadow.cljs :as-alias m]))

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


(defc build-card [build-id]
  (bind {::m/keys [build-status build-worker-active] :as data}
    (sg/kv-lookup ::m/build build-id))

  (bind build-warnings-count
    (count (:warnings build-status)))

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
                 [:span {:class (css :text-sm :font-medium :c-text-1l :truncate)} (name (:target (::m/build-config-raw data)))]]]]]]]

           (build-buttons build-id build-worker-active)]))))

(defc ui-builds-page []
  (bind builds
    (sg/query
      (fn [env]
        (->> (::m/build env)
             (keys)
             (sort)
             (vec)))))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto :py-2 [:sm :px-2])}
         [:div {:class (css :flex :flex-col)}
          [:div {:class (css :grid :grid-cols-1 :gap-2)}
           (sg/keyed-seq builds identity build-card)]]])))