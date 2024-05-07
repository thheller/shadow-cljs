(ns shadow.remote.runtime.writer
  (:import [shadow.remote.runtime LimitWriter LimitWriter$LimitReachedException]))

(defn pr-str-limit [obj limit]
  (let [lw (LimitWriter. limit)]
    (try
      (binding [*out* lw]
        (pr obj))
      (str "0," (.getString lw))
      (catch LimitWriter$LimitReachedException e
        (str "1," (.getString lw))))))

(defn limit-writer [limit]
  (LimitWriter. limit))

(defn get-string [^LimitWriter lw]
  (.getString lw))

(comment
  (pr-str-limit {:hello (range 10)} 20))
