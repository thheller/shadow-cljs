(ns shadow.cljs.ui.notifications
  (:require
    [clojure.string :as str]
    [cljs-test-display.favicon :as favicon]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]))

(def icon-red (favicon/color-data-url "#d00" 16))
(def icon-green (favicon/color-data-url "#0d0" 16))
(def icon-yellow (favicon/color-data-url "#FFFF00" 16))

(defn notification-summary [state]
  (->> state
       (sort-by first)
       (map (fn [[build-id status]]
              (str "[" build-id "]: " (case status
                                        :failed
                                        "FAILED!"
                                        0
                                        "Success."
                                        1
                                        "1 Warning."
                                        (str status " Warnings.")))
              ))
       (str/join "\n")))

;; FIXME: can't use notification in worker
(defn update-notification-state [before build-id status]
  (let [after (assoc before build-id status)]
    (if (= before after)
      before
      (do (js/console.log "state changed, fire notification" (pr-str after) (::ui-model/notifications state))
          (let [icon
                (cond
                  (->> after (vals) (some #(= :failed %)))
                  icon-red
                  (->> after (vals) (some pos?))
                  icon-yellow
                  :else
                  icon-green)]

            (when (::ui-model/notifications state)
              (let [n (js/Notification. "shadow-cljs"
                        #js {:silent true
                             :tag "shadow-cljs-build-status-notification"
                             :renotify true
                             :icon icon
                             :body (notification-summary after)})]
                (.addEventListener n "click"
                  (fn [e]
                    (js/goog.global.window.focus)))
                )))

          after))))


(defn count-warnings [{:keys [sources] :as info}]
  (reduce
    (fn [c {:keys [warnings]}]
      (+ c (count warnings)))
    0
    sources))

(defn process-worker-update
  [{:keys [db] :as env} {:keys [build-id type info] :as params}]
  (let [build-ident (db/make-ident ::m/build build-id)]

    (case type
      :build-complete
      {:db
       (-> db
           (update build-ident merge {::m/build-info info
                                      ;; FIXME: should actually update instead of just removing
                                      ;; but no access to the code from here
                                      ::m/build-ns-summary nil
                                      ::m/build-provides
                                      (->> (:sources info)
                                           (mapcat :provides)
                                           (sort)
                                           (into))}))}

      :build-failure
      {}

      ;; ignore
      state)))
