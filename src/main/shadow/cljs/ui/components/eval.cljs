(ns shadow.cljs.ui.components.eval
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.ui.loadable :refer (refer-lazy)]
    [shadow.cljs.model :as m]))

(refer-lazy shadow.cljs.ui.components.code-editor/codemirror)

(defc ui-repl-stream-item [{:keys [ident]}]
  [data
   (sg/query-ident ident
     [:code
      :ns
      {:result
       [::m/object-as-edn]}])]

  (<< [:div.bg-white.border.shadow.mb-2
       (pr-str data)]))

(defc ui-repl-page [runtime-ident]
  [{:keys [rid runtime-info] :as data}
   (sg/query-ident runtime-ident
     [:rid
      :runtime-info])

   process-eval-input!
   (fn [env code]
     (sg/run-tx env [::m/process-eval-input! runtime-ident code]))]

  (<< [:div.p-2 (str "#" rid " - " (str (:lang runtime-info "unknown lang"))
                     (when-some [ua (:user-agent runtime-info)]
                       (str " - " ua)))]
      [:div.p-2.text-xl "I have no idea how this should look?"]
      [:div.border-t-2.bg-white.px-2.py-1
       "Eval History - stream? - latest on top/bottom? results should be inspectable?"]
      [:div.border-t-2.overflow-y-auto.px-2.py-1.flex-1
       (sg/stream [::m/eval-stream runtime-ident] {} ui-repl-stream-item)]
      [:div.border-t-2.bg-white.px-2.py-1
       "Eval toolbar - current ns: user, ctrl+enter or shift+enter to eval"]
      [:div.bg-white.border-t-2 {:style {:height "120px"}}
       (codemirror
         {:on-submit process-eval-input!})]))

