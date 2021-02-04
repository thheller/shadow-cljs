(ns shadow.cljs.devtools.server.runtime)

(defonce instance-ref (atom nil))

(defn get-instance []
  @instance-ref)

(defn get-instance! []
  (let [inst @instance-ref]
    (when-not inst
      (throw (ex-info
               (str "shadow-cljs has not been started yet!\n"
                    "In embedded mode you need to call (shadow.cljs.devtools.server/start!) to start it.\n"
                    "If you have a shadow-cljs server or watch running then you are not connected to that process.") {})))
    inst))

(defn set-instance! [app]
  (reset! instance-ref app))

(defn reset-instance! []
  (reset! instance-ref nil))
