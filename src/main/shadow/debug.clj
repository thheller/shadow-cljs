(ns shadow.debug
  "backport 1.10 tap functionality"
  ;; cannot rely on my users adopting 1.10-alpha4+ and would be unable to use shadow-cljs
  ;; if I happen to leave a tap> call somewhere
  ;; tap> however is really useful and I want it now
  ;; I however think add-tap should behave like add-watch and require a key
  ;; (add-tap #(do-thing some-local %)) can't be removed otherwise
  ;; maybe I'm just not supposed to use it that way?
  (:refer-clojure :exclude (add-tap remove-tap tap>)))

(defonce ^:private taps-ref (atom {}))
(defonce ^:private ^java.util.concurrent.ArrayBlockingQueue tapq (java.util.concurrent.ArrayBlockingQueue. 1024))

(defn add-tap
  "adds f, a fn of one argument, to the tap set. This function will be called with anything sent via tap>.
  This function may (briefly) block (e.g. for streams), and will never impede calls to tap>,
  but blocking indefinitely may cause tap values to be dropped.
  Remember f in order to remove-tap"
  {:added "1.10"}
  [key f]
  (swap! taps-ref assoc key f)
  nil)

(defn remove-tap
  "remove f from the tap set the tap set."
  {:added "1.10"}
  [key]
  (swap! taps-ref dissoc key)
  nil)

;; less generic tap> that only allows maps with :tag
(defn tap> [x]
  {:pre [(map? x) (keyword? (:tag x))]}
  (.offer tapq x))

(defonce ^:private tap-loop
  (doto (Thread.
          #(let [x (.take tapq)
                 taps @taps-ref]
             (doseq [[_ tap] taps]
               (try
                 (tap x)
                 (catch Throwable ex)))
             (recur))
          "shadow.debug/tap-loop")
    (.setDaemon true)
    (.start)))
