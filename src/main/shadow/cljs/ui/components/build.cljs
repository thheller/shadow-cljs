(ns shadow.cljs.ui.components.build
  (:require
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.builds :as builds]))

(defc tab-status [build-ident]
  (bind {::m/keys [build-status] :as data}
    (sg/query-ident build-ident
      [::m/build-status]))

  (render
    (<< [:div.bg-white.flex-1.overflow-y-auto
         [:div.border-t.border-gray-200.px-2.py-4
          (build-status/render-build-status-full build-status)]])))

(defc tab-runtimes [build-ident]
  (bind {::m/keys [build-runtimes] :as data}
    (sg/query-ident build-ident
      [::m/build-runtimes]))

  (render
    (<< [:div.flex-1.overflow-y-auto
         (runtimes/ui-runtime-list build-runtimes)])))

(def class-tab-selected
  "whitespace-nowrap py-3 px-5 border-b-2 font-medium")

(def class-tab-normal
  "border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300 whitespace-nowrap py-3 px-5 border-b-2 font-medium")

(defc page-header [build-ident tab]
  ;; FIXME: link helpers, shouldn't use str
  (bind {::m/keys [build-runtime-count build-worker-active]}
    (sg/query-ident build-ident
      [::m/build-runtime-count
       ::m/build-worker-active]))

  (render
    (let [link-root (str "/build/" (-> build-ident second name))]

      (<< [:div.shadow-lg.mb-4
           (builds/build-card build-ident)]

          [:div.flex.flex-col
           [:div.align-middle.min-w-full
            [:div
             [:nav.-mb-px.flex {:aria-label "Tabs"}
              [:a
               {:class (if (= tab :status) class-tab-selected class-tab-normal)
                :ui/href link-root}
               "Status"]
              (when build-worker-active
                (<< [:a
                     {:class (if (= tab :runtimes) class-tab-selected class-tab-normal)
                      :ui/href (str link-root "/runtimes")}
                     (str "Runtimes (" build-runtime-count ")")]))]]]]))))

(defn ui-page-runtimes [build-ident]
  (<< (page-header build-ident :runtimes)
      (tab-runtimes build-ident)))

(defn ui-page-status [build-ident]
  (<< (page-header build-ident :status)
      (tab-status build-ident)))