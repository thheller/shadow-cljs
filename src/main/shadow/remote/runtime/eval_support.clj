(ns shadow.remote.runtime.eval-support
  (:require [shadow.remote.runtime.api :as p]
            [shadow.remote.runtime.obj-support :as obj-support]
            [clojure.datafy :as d]))

(def ^:dynamic get-ref nil)

(defn get-ref* [obj-support obj-id]
  (obj-support/get-ref obj-support obj-id))

(defn eval-clj
  [{:keys [runtime obj-support]}
   {:keys [code ns]
    :or {ns 'user}
    :as msg}]

  (binding [*ns* (find-ns ns)
            get-ref #(get-ref* obj-support %)]
    (try
      (let [val (read-string code)
            res (eval val)

            ref-oid
            (obj-support/register obj-support res {:code code
                                                   :ns ns})]

        (p/reply runtime msg
          {:op :eval-result-ref
           :ref-oid ref-oid}))

      (catch Exception e
        (p/reply runtime msg
          {:op :eval-error
           :e (d/datafy e)})))))

(defn start [runtime obj-support]
  (let [svc
        {:runtime runtime
         :obj-support obj-support}]

    (p/add-extension runtime
      ::ext
      {:ops
       {:eval-clj #(eval-clj svc %)}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))
