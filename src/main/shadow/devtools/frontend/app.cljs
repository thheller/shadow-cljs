(ns shadow.devtools.frontend.app
  (:require [shadow.api :refer (ns-ready)]
            [shadow.dom :as dom]
            [shadow.vault.store :as store]
            [shadow.vault.dom :as vdom]
            [shadow.react.multi :as multi]
            [shadow.router :as router]
            [shadow.devtools.frontend.components.root :as root]
            [shadow.devtools.frontend.components.model :as m]
            [shadow.devtools.frontend.components.actions :as a]
            [shadow.devtools.frontend.components.handler :as handler]))

(defonce store (store/empty))

(def routes
  [{:match ["dashboard"]
    :enter
    (fn [vault props]
      (multi/select root/sections :dashboard {}))}])

(defn start []
  (js/console.warn ::start)

  (let [container
        (dom/by-id "app")

        {::store/keys [vault] :as context}
        (-> {}
            (store/context store [handler/app-handler]))]

    (router/control!
      container
      (root/container {})
      {:routes routes
       :context context
       :start-token "/dashboard"
       :use-fragment true})
    ))

(defn stop []
  (js/console.warn ::stop)
  (let [container
        (dom/by-id "app")]

    (vdom/unmount container)))

(ns-ready)
