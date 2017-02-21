(ns shadow.devtools.frontend.components.handler
  (:require [shadow.vault.store :as store]
            [shadow.devtools.frontend.components.actions :as a]
            [shadow.devtools.frontend.components.model :as m]))

(defn ->reduce [init reduce-fn items]
  (reduce reduce-fn init items))

(defn app-handler [vault action]
  (store/action-case action
    [a/import-builds builds]
    (-> vault
        (assoc m/Builds [])
        (->reduce
          (fn [vault {:keys [id] :as build}]
            (let [key (m/Build id)]
              (-> vault
                  (assoc key build)
                  (update m/Builds conj key))))
          builds))

    vault))
