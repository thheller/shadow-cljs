(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.model :as m]
    ))

(defn build-buttons [build-id build-worker-active]
  (if build-worker-active
    (<< [:button.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click [::m/build-watch-compile! build-id]}
         "force compile"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click [::m/build-watch-stop! build-id]}
         "stop watch"])

    (<< [:button.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click [::m/build-watch-start! build-id]}
         "start watch"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click [::m/build-compile! build-id]}
         "compile"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click [::m/build-release! build-id]}
         "release"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click [::m/build-release-debug! build-id]}
         "release debug"])))

;; https://github.com/sschoger/heroicons-ui

(def icon-build-busy
  (<< [:svg
       {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :width "24" :height "24"}
       [:g
        [:path
         {:d "M9 4.58V4c0-1.1.9-2 2-2h2a2 2 0 0 1 2 2v.58a8 8 0 0 1 1.92 1.11l.5-.29a2 2 0 0 1 2.74.73l1 1.74a2 2 0 0 1-.73 2.73l-.5.29a8.06 8.06 0 0 1 0 2.22l.5.3a2 2 0 0 1 .73 2.72l-1 1.74a2 2 0 0 1-2.73.73l-.5-.3A8 8 0 0 1 15 19.43V20a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-.58a8 8 0 0 1-1.92-1.11l-.5.29a2 2 0 0 1-2.74-.73l-1-1.74a2 2 0 0 1 .73-2.73l.5-.29a8.06 8.06 0 0 1 0-2.22l-.5-.3a2 2 0 0 1-.73-2.72l1-1.74a2 2 0 0 1 2.73-.73l.5.3A8 8 0 0 1 9 4.57zM7.88 7.64l-.54.51-1.77-1.02-1 1.74 1.76 1.01-.17.73a6.02 6.02 0 0 0 0 2.78l.17.73-1.76 1.01 1 1.74 1.77-1.02.54.51a6 6 0 0 0 2.4 1.4l.72.2V20h2v-2.04l.71-.2a6 6 0 0 0 2.41-1.4l.54-.51 1.77 1.02 1-1.74-1.76-1.01.17-.73a6.02 6.02 0 0 0 0-2.78l-.17-.73 1.76-1.01-1-1.74-1.77 1.02-.54-.51a6 6 0 0 0-2.4-1.4l-.72-.2V4h-2v2.04l-.71.2a6 6 0 0 0-2.41 1.4zM12 16a4 4 0 1 1 0-8 4 4 0 0 1 0 8zm0-2a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"}]
        [:animateTransform
         {:attributeType "xml"
          :attributeName "transform"
          :type "rotate"
          :from "0 12 12"
          :to "360 12 12"
          :dur "2s"
          :repeatCount "indefinite"}]]]))

(def icon-build-success
  (<< [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :width "24" :height "24" :style "fill: green;"}
       [:path {:d "M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20zm0-2a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm-3.54-4.46a1 1 0 0 1 1.42-1.42 3 3 0 0 0 4.24 0 1 1 0 0 1 1.42 1.42 5 5 0 0 1-7.08 0zM9 11a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm6 0a1 1 0 1 1 0-2 1 1 0 0 1 0 2z"}]]))

(def icon-build-error
  (<< [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :width "24" :height "24" :style "fill: red;"}
       [:path {:d "M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20zm0-2a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm-3.54-4.54a5 5 0 0 1 7.08 0 1 1 0 0 1-1.42 1.42 3 3 0 0 0-4.24 0 1 1 0 0 1-1.42-1.42zM9 11a1 1 0 1 1 0-2 1 1 0 0 1 0 2zm6 0a1 1 0 1 1 0-2 1 1 0 0 1 0 2z"}]]))

(def icon-build-warnings
  (<< [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :width "24" :height "24" :style "fill: orange;"}
       [:path {:d "M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20zm0-2a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm0-9a1 1 0 0 1 1 1v4a1 1 0 0 1-2 0v-4a1 1 0 0 1 1-1zm0-4a1 1 0 1 1 0 2 1 1 0 0 1 0-2z"}]]))

(def icon-build-missing
  (<< [:svg {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :width "24" :height "24" :style "fill: rgba(0,0,0,0.1);"}
       [:path {:d "M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20zm0-2a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm0-9a1 1 0 0 1 1 1v4a1 1 0 0 1-2"}]]))

(defc ui-builds-entry [build-ident]
  [{::m/keys [build-id build-worker-active build-warnings-count build-status] :as data}
   (sg/query-ident build-ident
     [::m/build-id
      ::m/build-worker-active
      ::m/build-warnings-count
      {::m/build-status
       [:status]}])]
  (let [{:keys [status]} build-status]
    (<< [:div.px-4.py-2
         [:div.border.rounded.bg-white.p-4
          [:div.flex
           [:div.self-center.pr-4
            [:a.cursor-pointer {:href (str "/build/" (name build-id))}
             (case status
               :compiling
               icon-build-busy

               :completed
               (if (zero? build-warnings-count)
                 icon-build-success
                 icon-build-warnings)

               :failed
               icon-build-error

               :inactive
               icon-build-missing

               :pending
               icon-build-busy

               icon-build-missing)]]
           [:div.flex-1
            [:div.pb-2
             [:a.font-bold.text-lg {:href (str "/build/" (name build-id))} (name build-id)]]
            [:div
             (build-buttons build-id build-worker-active)
             ]]]]])))

(defc ui-builds-page []
  [{::m/keys [builds]}
   (sg/query-root [::m/builds])

   ::m/build-watch-compile! sg/tx
   ::m/build-watch-start! sg/tx
   ::m/build-watch-stop! sg/tx
   ::m/build-compile! sg/tx
   ::m/build-release! sg/tx
   ::m/build-release-debug! sg/tx]

  (<< [:div.flex-1.overflow-auto.pt-2
       ;; [:div.p-4.px-8 "start all / stop all"]
       (sg/render-seq builds identity ui-builds-entry)]))

(defc ui-build-overview [build-ident]
  [{::m/keys [build-sources-sorted] :as data}
   (sg/query-ident build-ident
     [::m/build-sources-sorted])

   state-ref (atom {:selected nil})

   selected (sg/watch state-ref [:selected])

   id->src
   (into {} (map (juxt :resource-id identity)) build-sources-sorted)

   id->idx
   (reduce-kv
     (fn [m idx {:keys [resource-id] :as v}]
       (assoc m resource-id idx))
     {}
     build-sources-sorted)

   ::highlight
   (fn [env e resource-id]
     (swap! state-ref assoc :selected resource-id))

   render-source-entry
   (fn [{:keys [resource-id resource-name] :as item}]
     (let [selected? (= resource-id selected)]
       (<< [:div.text-xs
            {:class (when selected? "font-bold")
             :on-mouseenter [::highlight resource-id]}
            resource-name])))]

  (<< [:div.p-2
       [:div.py-2.text-xl (count build-sources-sorted) " Namespaces used in build"]
       [:div.flex
        [:div.flex-1 "left"]
        [:div
         (sg/render-seq build-sources-sorted nil render-source-entry)]
        [:div.flex-1 "right"]]]))

(defc ui-build-page [build-ident]
  [data
   (sg/query-ident build-ident
     [:db/ident
      ::m/build-id
      ::m/build-target
      ::m/build-worker-active
      ::m/build-status])

   ::m/build-watch-compile! sg/tx
   ::m/build-watch-start! sg/tx
   ::m/build-watch-stop! sg/tx
   ::m/build-compile! sg/tx
   ::m/build-release! sg/tx
   ::m/build-release-debug! sg/tx]

  (let [{::m/keys [build-id build-target build-status build-worker-active]} data]
    (<< [:div
         [:h1.text-xl.px-2.py-4 (name build-id) " - " (name build-target)]
         [:div.p-2 (build-buttons build-id build-worker-active)]]

        (build-status/render-build-status-full build-status)

        )))


