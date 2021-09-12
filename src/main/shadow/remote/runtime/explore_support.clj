(ns shadow.remote.runtime.explore-support
  (:require
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.obj-support :as obj-support]
    ))

(defn namespaces [{:keys [runtime] :as svc} msg]
  (shared/reply runtime msg
    {:op :explore/namespaces-result
     :namespaces (->> (all-ns)
                      (map #(.getName ^clojure.lang.Namespace %))
                      (sort-by name)
                      (vec))}))

(defn namespace-vars [{:keys [runtime] :as svc} {:keys [ns] :as msg}]
  (shared/reply runtime msg
    {:op :explore/namespace-vars-result
     :vars (->> (ns-publics ns)
                (keys)
                (sort-by name)
                (vec))}))

(defn describe-ns [{:keys [runtime] :as svc} {:keys [ns] :as msg}]
  (shared/reply runtime msg
    {:op :explore/describe-ns-result
     :description {}}))

(defn describe-var [{:keys [runtime] :as svc} {:keys [var] :as msg}]
  (let [var (find-var var)
        m (meta var)]

    (shared/reply runtime msg
      {:op :explore/describe-var-result
       :description (dissoc m :ns :name)})))

(defn start [runtime obj-support]
  (let [svc
        {:runtime runtime
         :obj-support obj-support}]

    (p/add-extension runtime
      ::ext
      {:ops
       {:explore/namespaces #(namespaces svc %)
        :explore/namespace-vars #(namespace-vars svc %)
        :explore/describe-var #(describe-var svc %)
        :explore/describe-ns #(describe-ns svc %)}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))


