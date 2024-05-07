(ns shadow.remote.runtime.writer
  (:import [goog.string StringBuffer]))

(deftype LimitWriter [^StringBuffer sb limit]
  Object
  (getString [this]
    (.toString sb))

  IWriter
  (-write [_ s]
    (.append sb s)
    (when (>= (.getLength sb) limit)
      (throw (ex-info (str "The limit of " limit " bytes was reached while printing.") {:tag ::limit-reached :limit limit}))))
  (-flush [_] nil))

(defn pr-str-limit [obj limit]
  (let [sb (StringBuffer.)
        writer (LimitWriter. sb limit)]
    (try
      (pr-writer obj writer (pr-opts))
      (str "0," (.toString sb))
      (catch :default e
        (if-not (keyword-identical? ::limit-reached (:tag (ex-data e)))
          (throw e)
          (str "1,"
               (let [s (.toString sb)]
                 (if (> (.-length s) limit)
                   (subs s 0 limit)
                   s))))))))

(defn limit-writer [limit]
  (let [sb (StringBuffer.)]
    (LimitWriter. sb limit)))

(defn get-string [^LimitWriter lw]
  (.getString lw))

(comment
  (pr-str-limit {:hello (range 10)} 20))
