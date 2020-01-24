(ns shadow.remote.runtime.cljs.env
  (:require [clojure.set :as set]))

(defonce runtime-ref (atom nil))
(defonce extensions-ref (atom {}))

(defn start-all-extensions! []
  (let [started-set (set (keys @runtime-ref))
        exts @extensions-ref
        ext-set (set (keys exts))
        pending-set (set/difference ext-set started-set)]

    ;; FIXME: this is dumb, should properly sort things in dependency order
    ;; instead of looping over
    (loop [pending-set pending-set]
      (cond
        (empty? pending-set)
        ::done!

        :else
        (-> (reduce
              (fn [pending-set ext-id]
                (let [{:keys [depends-on init-fn] :as ext} (get exts ext-id)]
                  (if (some pending-set depends-on)
                    pending-set
                    (let [started (init-fn @runtime-ref)]
                      (swap! runtime-ref assoc ext-id started)
                      (disj pending-set ext-id)))))
              pending-set
              pending-set)
            (recur))))))

(defn init-runtime! [env]
  (reset! runtime-ref env)


  (when (seq @extensions-ref)
    (start-all-extensions!)))

(defn init-extension! [ext-id depends-on init-fn stop-fn]
  (when-some [started (get @runtime-ref ext-id)]
    (let [{:keys [stop-fn] :as old} (get @extensions-ref ext-id)]
      (stop-fn started)
      (swap! runtime-ref dissoc ext-id)))

  (swap! extensions-ref assoc ext-id {:ext-id ext-id
                                      :depends-on depends-on
                                      :init-fn init-fn
                                      :stop-fn stop-fn})

  (when @runtime-ref
    (start-all-extensions!)))