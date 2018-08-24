(ns shadow.cljs.ui.main-cards
  (:require
    [nubank.workspaces.core :as ws]
    [nubank.workspaces.model :as wsm]
    [nubank.workspaces.card-types.react :as ct.react :refer (react-card)]
    [cljs.test :refer [is]]
    [shadow.markup.react :as html :refer ($)]
    [fulcro.client.dom :as dom]))

(ws/defcard hello-card
  (react-card
    (html/div {} "Hello World!")))

(defonce counter (atom 0))

(ws/defcard counter-example-card
  (react-card
    counter
    (html/div {}
      (str "Count: " @counter)
      (html/button {:onClick #(swap! counter inc)} "+"))))

(ws/deftest sample-test
  (is (= 1 1)))

(ws/defcard styles-card
  {::wsm/node-props {:style {:background "red" :color "white"}}}
  (ct.react/react-card
    (dom/div "I'm in red!")))

(def purple-card {::wsm/node-props {:style {:background "#79649a"}}})
(def align-top {::wsm/align {:flex 1}})

(ws/defcard widget-card
  {::wsm/card-width 3 ::wsm/card-height 7}
  purple-card
  align-top
  (ct.react/react-card
    (dom/div "💜")))
