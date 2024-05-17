(ns shadow.cljs.ui.components.build
  (:require
    [shadow.css :refer (css)]
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs :as-alias m]
    [shadow.cljs.ui.components.builds :as builds]
    [shadow.grove.ui.edn :as edn]))

(defc tab-status [build-id]
  (bind build-status
    (sg/kv-lookup ::m/build build-id ::m/build-status))

  (render
    (<< [:div {:class (css :bg-white :flex-1 :overflow-y-auto)}
         [:div {:class (css :border-t :border-gray-200 :px-2 :py-4)}
          (build-status/render-build-status-full build-status)]])))

(defn q-build-runtimes
  [env build-id]
  (->> (::m/runtime env)
       (vals)
       (filter (fn [{:keys [runtime-info]}]
                 (and (= :cljs (:lang runtime-info))
                      (= build-id (:build-id runtime-info)))))
       (mapv :runtime-id)))

(defc tab-runtimes [build-id]
  (bind build-runtimes
    (sg/query q-build-runtimes build-id))

  (render
    (<< [:div {:class (css :flex-1 :overflow-y-auto)}
         (runtimes/ui-runtime-list build-runtimes)])))

(defc tab-config [build-id]
  (bind build-config-raw
    (sg/kv-lookup ::m/build build-id ::m/build-config-raw))

  (render
    (<< [:div {:class (css :flex-1 :overflow-y-auto :bg-white)}
         (edn/render-edn build-config-raw)])))

(def $tab-selected
  (css :whitespace-nowrap :py-3 :px-5 :border-b-2 :font-medium))

(def $tab-normal
  (css :border-transparent :text-gray-500 :whitespace-nowrap :py-3 :px-5 :border-b-2 :font-medium
    [:hover :text-gray-700 :border-gray-300]))

(defc page-header [build-id tab]
  (bind {::m/keys [build-worker-active]}
    (sg/kv-lookup ::m/build build-id))

  (bind build-runtime-count
    (count (sg/query q-build-runtimes build-id)))

  (render
    (let [link-root (str "/build/" (name build-id))]

      (<< [:div {:class (css :shadow-lg :mb-4)}
           (builds/build-card build-id)]

          [:div {:class (css :flex :flex-col)}
           [:div {:class (css :align-middle :min-w-full)}
            [:div
             [:nav
              {:class (css :flex {:margin-bottom "-1px"})
               :aria-label "Tabs"}
              [:a
               {:class (if (= tab :status) $tab-selected $tab-normal)
                :ui/href link-root}
               "Status"]
              [:a
               {:class (if (= tab :config) $tab-selected $tab-normal)
                :ui/href (str link-root "/config")}
               "Config"]
              (when build-worker-active
                (<< [:a
                     {:class (if (= tab :runtimes) $tab-selected $tab-normal)
                      :ui/href (str link-root "/runtimes")}
                     (str "Runtimes (" build-runtime-count ")")]))]]]]))))

(defn ui-page [build-id tab]
  (<< (page-header build-id tab)
      (case tab
        :runtimes (tab-runtimes build-id)
        :config (tab-config build-id)
        (tab-status build-id)
        )))