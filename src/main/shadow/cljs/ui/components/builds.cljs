(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]))


(defn build-buttons [build-id build-worker-active]
  (if build-worker-active
    (<< [:button.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click {:e ::m/build-watch-compile! :build-id build-id}}
         "force compile"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click {:e ::m/build-watch-stop! :build-id build-id}}
         "stop watch"])

    (<< [:button.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click {:e ::m/build-watch-start! :build-id build-id}}
         "start watch"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click {:e ::m/build-compile! :build-id build-id}}
         "compile"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click {:e ::m/build-release! :build-id build-id}}
         "release"]
        [:button.ml-2.py-1.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
         {:on-click {:e ::m/build-release-debug! :build-id build-id}}
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
       [:path {:d "M12 22a10 10 0 1 1 0-20 10 10 0 0 1 0 20zm0-2a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm0-9a1 1 0 0 1 1 1v4a1 1 0 0 1-2 0v-4a1 1 0 0 1 1-1zm0-4a1 1 0 1 1 0 2 1 1 0 0 1 0-2z"}]]))

(defc ui-builds-entry [build-ident]
  (bind {::m/keys [build-id build-worker-active build-warnings-count build-status] :as data}
    (sg/query-ident build-ident
      [::m/build-id
       ::m/build-worker-active
       ::m/build-warnings-count
       {::m/build-status
        [:status]}]))

  (render
    (let [{:keys [status]} build-status]
      (<< [:div.border-b.bg-white.p-4.flex
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
            [:div.pb-1
             [:a.font-bold.text-lg {:href (str "/build/" (name build-id))} (name build-id)]]
            [:div
             (build-buttons build-id build-worker-active)]]]))))


(defc ui-builds-page []
  (bind {::m/keys [builds]}
    (sg/query-root [::m/builds]))

  (event ::m/build-watch-compile! sg/tx)
  (event ::m/build-watch-start! sg/tx)
  (event ::m/build-watch-stop! sg/tx)
  (event ::m/build-compile! sg/tx)
  (event ::m/build-release! sg/tx)
  (event ::m/build-release-debug! sg/tx)

  (render
    (<<
      [:div.flex-1.overflow-auto.py-2
       [:div.max-w-7xl.mx-auto
        [:div.bg-white
         [:div.pl-4.pr-6.pt-4.pb-4.border-b.border-t.border-gray-200.sm:pl-6.lg:pl-8.xl:pl-6.xl:pt-6.xl:border-t-0]
         [:ul.relative.z-0.divide-y.divide-gray-200.border-b.border-gray-200
          [:li.relative.pl-4.pr-6.py-5.hover:bg-gray-50.sm:py-6.sm:pl-6.lg:pl-8.xl:pl-6
           [:div.flex.items-center.justify-between.space-x-4
            [:div.min-w-0.space-y-3
             [:div.flex.items-center.space-x-3
              [:span.h-4.w-4.bg-green-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
               [:span.h-2.w-2.bg-green-400.rounded-full]]
              [:span.block
               [:h2.text-sm.font-medium
                [:a {:href "#"}
                 [:span.absolute.inset-0 {:aria-hidden "true"}] "Workcation" [:span.sr-only "Running"]]]]]
             [:a.relative.group.flex.items-center.space-x-2.5 {:href "#"}
              [:svg.flex-shrink-0.w-5.h-5.text-gray-400.group-hover:text-gray-500 {:viewBox "0 0 18 18" :fill "none" :xmlns "http://www.w3.org/2000/svg" :aria-hidden "true"}
               [:path {:fill-rule "evenodd" :clip-rule "evenodd" :d "M8.99917 0C4.02996 0 0 4.02545 0 8.99143C0 12.9639 2.57853 16.3336 6.15489 17.5225C6.60518 17.6053 6.76927 17.3277 6.76927 17.0892C6.76927 16.8762 6.76153 16.3104 6.75711 15.5603C4.25372 16.1034 3.72553 14.3548 3.72553 14.3548C3.31612 13.316 2.72605 13.0395 2.72605 13.0395C1.9089 12.482 2.78793 12.4931 2.78793 12.4931C3.69127 12.5565 4.16643 13.4198 4.16643 13.4198C4.96921 14.7936 6.27312 14.3968 6.78584 14.1666C6.86761 13.5859 7.10022 13.1896 7.35713 12.965C5.35873 12.7381 3.25756 11.9665 3.25756 8.52116C3.25756 7.53978 3.6084 6.73667 4.18411 6.10854C4.09129 5.88114 3.78244 4.96654 4.27251 3.72904C4.27251 3.72904 5.02778 3.48728 6.74717 4.65082C7.46487 4.45101 8.23506 4.35165 9.00028 4.34779C9.76494 4.35165 10.5346 4.45101 11.2534 4.65082C12.9717 3.48728 13.7258 3.72904 13.7258 3.72904C14.217 4.96654 13.9082 5.88114 13.8159 6.10854C14.3927 6.73667 14.7408 7.53978 14.7408 8.52116C14.7408 11.9753 12.6363 12.7354 10.6318 12.9578C10.9545 13.2355 11.2423 13.7841 11.2423 14.6231C11.2423 15.8247 11.2313 16.7945 11.2313 17.0892C11.2313 17.3299 11.3937 17.6097 11.8501 17.522C15.4237 16.3303 18 12.9628 18 8.99143C18 4.02545 13.97 0 8.99917 0Z" :fill "currentcolor"}]]
              [:span.text-sm.text-gray-500.group-hover:text-gray-900.font-medium.truncate "debbielewis/workcation"]]]
            [:div.sm:hidden
             [:svg.h-5.w-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
              [:path {:fill-rule "evenodd" :d "M7.293 14.707a1 1 0 010-1.414L10.586 10 7.293 6.707a1 1 0 011.414-1.414l4 4a1 1 0 010 1.414l-4 4a1 1 0 01-1.414 0z" :clip-rule "evenodd"}]]]
            [:div.hidden.sm:flex.flex-col.flex-shrink-0.items-end.space-y-3
             [:p.flex.items-center.space-x-4
              [:a.relative.text-sm.text-gray-500.hover:text-gray-900.font-medium {:href "#"} "Visit site"]
              [:button.relative.bg-white.rounded-full.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-indigo-500 {:type "button"}
               [:span.sr-only "Add to favorites"]
               [:svg.h-5.w-5.text-yellow-300.hover:text-yellow-400 {:xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
                [:path {:d "M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z"}]]]]
             [:p.flex.text-gray-500.text-sm.space-x-2
              [:span "Laravel"]
              [:span {:aria-hidden "true"} "&middot;"]
              [:span "Last deploy 3h ago"]
              [:span {:aria-hidden "true"} "&middot;"]
              [:span "United states"]]]]]]]
        [:div.flex.flex-col.mt-2
         [:div.align-middle.min-w-full.overflow-x-auto.shadow.overflow-hidden.xl:rounded-lg
          (sg/render-seq builds identity ui-builds-entry)]]]])))


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
           (sg/render-seq build-sources-sorted nil render-source-entry)]
          [:div.flex-1 "right"]]])))

(defc ui-build-runtimes [build-ident]
  (bind {::m/keys [build-runtimes] :as data}
    (sg/query-ident build-ident
      [::m/build-runtimes]))

  (render
    (let [runtime-count (count build-runtimes)]
      (<< [:div.px-2
           [:div.pt-2
            (condp = runtime-count
              0 "No connected runtimes."
              1 "1 connected runtime:"
              (str runtime-count " connected runtimes:"))]

           (runtimes/ui-runtime-listing build-runtimes)]))))

(defc ui-build-page [build-ident]
  (bind data
    (sg/query-ident build-ident
      [:db/ident
       ::m/build-id
       ::m/build-target
       ::m/build-worker-active
       ::m/build-status]))

  (event ::m/build-watch-compile! sg/tx)
  (event ::m/build-watch-start! sg/tx)
  (event ::m/build-watch-stop! sg/tx)
  (event ::m/build-compile! sg/tx)
  (event ::m/build-release! sg/tx)
  (event ::m/build-release-debug! sg/tx)

  (render
    (let [{::m/keys [build-id build-target build-status build-worker-active]} data]
      (<< [:div
           [:div.px-2
            [:h1.text-xl.pt-4 (name build-id)]
            [:div " target: " (name build-target)]]
           [:div.p-2 (build-buttons build-id build-worker-active)]]

          (when build-worker-active
            (ui-build-runtimes build-ident))

          (build-status/render-build-status-full build-status)))))




