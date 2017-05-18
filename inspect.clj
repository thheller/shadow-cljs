
(require '[clojure.string :as str])
(def ^:dynamic *depth* 0)

(alter-var-root
  #'clojure.core/load
  (fn [actual]
    (fn [& args]
      (let [start
            (System/currentTimeMillis)]

        (binding [*depth* (inc *depth*)]
          (prn [(->> (repeat *depth* ".") (str/join "") (symbol)) "START" args])

          (let [result (apply actual args)

                runtime (- (System/currentTimeMillis)
                           start)]


            (prn [(->> (repeat *depth* ".") (str/join "") (symbol)) "FINISH" runtime args])

            result))
        ))))
