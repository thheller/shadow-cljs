(ns shadow.cljs.ui.components.eval
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.ui.loadable :refer (refer-lazy)]
    [shadow.cljs.model :as m]))

(refer-lazy shadow.cljs.ui.components.code-editor/codemirror)

(defn ui-eval-item [data]
  (<< [:div.bg-white.border.shadow.mb-2
       [:pre "(+\n 1\n 2)"]
       (pr-str data)]))

(defc ui-repl-page [runtime-ident]
  [{:keys [rid runtime-info] :as data}
   (sg/query-ident runtime-ident
     [:rid
      :runtime-info
      :supported-ops
      ::m/eval-history])

   process-eval-input!
   (fn [env code]
     (sg/run-tx env [::m/process-eval-input! runtime-ident code]))]

  (<< [:div.p-2 (str "#" rid " - " (str (:lang runtime-info "unknown lang"))
                     (when-some [ua (:user-agent runtime-info)]
                       (str " - " ua)))]
    [:div.border-t-2.bg-white.px-2.py-1
     "Eval History - stream? - latest on bottom?"]
    [:div.border-t-2.overflow-y-auto.px-2.py-1.flex-1
     (sg/render-seq (::m/eval-history data) :id ui-eval-item)]
    [:div.border-t-2.bg-white.px-2.py-1
     "Eval toolbar - current ns: user, ctrl+enter or shift+enter to eval"]
    [:div.bg-white.border-t-2 {:style {:height "120px"}}
     (codemirror
       {:on-submit process-eval-input!})]))

