(ns shadow.remote.runtime.writer
  (:import [goog.string StringBuffer]))

(deftype LimitWriter [^StringBuffer sb limit]
  IWriter
  (-write [_ s]
    (.append sb s)
    (when (>= (.getLength sb) limit)
      (throw (ex-info "limit reached" {:tag ::limit-reached}))))
  (-flush [_] nil))

(defn pr-str-limit [obj limit]
  (let [sb (StringBuffer.)
        writer (LimitWriter. sb limit)]
    (try
      (pr-writer obj writer (pr-opts))
      [false (.toString sb)]
      (catch :default e
        (if-not (keyword-identical? ::limit-reached (:tag (ex-data e)))
          (throw e)
          [true
           (let [s (.toString sb)]
             (if (> (.-length s) limit)
               (subs s 0 limit)
               s))])))))

(comment
  (pr-str-limit {:hello (range 10)} 20))
