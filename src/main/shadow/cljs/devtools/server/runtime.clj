(ns shadow.cljs.devtools.server.runtime)

(defonce instance-ref (volatile! nil))

(defn get-instance []
  @instance-ref)

(defn get-instance! []
  (let [inst @instance-ref]
    (when-not inst
      (throw (ex-info "missing instance" {})))
    inst))

(defn set-instance! [app]
  (vreset! instance-ref app))

(defn reset-instance! []
  (vreset! instance-ref nil))
