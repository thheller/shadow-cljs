(ns demo.foreign)

(defn ^:export foo [x]
  (.helloWorld x))


(defprotocol IBar
  (bar [x]))

(defrecord Bar [x]
  IBar
  (bar [_] (str "Bar" x)))

(defrecord Bar2 [x]
  IBar
  (bar [_] (str "Bar2" x)))

(defn do-it [^not-native x]
  (str "do-it" (bar x)))

(js/console.log (do-it (Bar. "x")))
(js/console.log (satisfies? IBar (Bar. "x")))
(js/console.log (implements? IBar (Bar. "x")))

(defn dest [{:keys [foo] :as opts}]
  foo)

(js/console.log (dest {:foo "foo"}))

