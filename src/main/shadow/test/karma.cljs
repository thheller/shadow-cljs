(ns shadow.test.karma
  (:require [shadow.test :as st]
            [shadow.test.env :as env]
            [fipp.clojure :refer (pprint)]
            [clojure.string :as s]
            [clojure.data :as data]
            [cljs.test :as ct]))

;; https://github.com/honzabrecka/karma-reporter/blob/master/src/jx/reporter/karma.cljs
;; removed all the parts we don't need

(defn- now []
  (js/Date.now))

(defn- indent [n s]
  (let [indentation (reduce str "" (repeat n " "))]
    (clojure.string/replace s #"\n" (str "\n" indentation))))

(defn- remove-last-new-line [s]
  (subs s 0 (dec (count s))))

(defn- format-fn [indentation [c & q]]
  (let [e (->> q
               (map #(with-out-str (pprint %)))
               (apply str)
               (str "\n"))]
    (str "(" c (indent (+ indentation 2) (remove-last-new-line e)) ")")))

(defn- format-diff [indentation assert [c a b & q]]
  (when (and (= c '=) (= (count assert) 3) (nil? q))
    (let [format (fn [sign value]
                   (str sign " "
                        (if value
                          (indent (+ indentation 2)
                            (-> value
                                (pprint)
                                (with-out-str)
                                (remove-last-new-line)))
                          "\n")))
          [removed added] (data/diff a b)]
      (str (format "-" removed)
           (format (str "\n" (apply str (repeat indentation " ")) "+") added)))))

(defn- format-log [{:keys [expected actual message testing-contexts-str] :as result}]
  (let [indentation (count "expected: ")]
    (str
      "FAIL in   " (ct/testing-vars-str result) "\n"
      (when-not (s/blank? testing-contexts-str)
        (str "\"" testing-contexts-str "\"\n"))
      (if (and (seq? expected)
               (seq? actual))
        (str
          "expected: " (format-fn indentation expected) "\n"
          "  actual: " (format-fn indentation (second actual)) "\n"
          (when-let [diff (format-diff indentation expected (second actual))]
            (str "    diff: " diff "\n")))
        (str
          expected " failed with " actual "\n"))
      (when message
        (str " message: " (indent indentation message) "\n")))))

(def test-var-result (volatile! []))

(def test-var-time-start (volatile! (now)))

(defmethod ct/report :karma [_])

;; By default, all report types for :cljs.test reporter are printed
(derive ::karma :ct/default)

;; Do not print "Ran <t> tests containing <a> assertions."
(defmethod ct/report [::karma :summary] [_])

(defmethod ct/report [::karma :begin-test-ns] [m]
  (println "Testing" (name (:ns m))))

(defmethod ct/report [::karma :begin-test-var] [_]
  (vreset! test-var-time-start (now))
  (vreset! test-var-result []))

(defmethod ct/report [::karma :end-test-var] [m]
  (let [var-meta (meta (:var m))]
    (-> {:suite [(:ns var-meta)]
         :description (:name var-meta)
         :success (zero? (count @test-var-result))
         :skipped nil
         :time (- (now) @test-var-time-start)
         :log (map format-log @test-var-result)}
        (clj->js)
        (js/__karma__.result))))

(defmethod ct/report [::karma :fail] [m]
  (ct/inc-report-counter! :fail)
  (vswap! test-var-result conj (assoc m :testing-contexts-str (ct/testing-contexts-str))))

(defmethod ct/report [::karma :error] [m]
  (ct/inc-report-counter! :error)
  (vswap! test-var-result conj (assoc m :testing-contexts-str (ct/testing-contexts-str))))

(defmethod ct/report [::karma :end-run-tests] [_]
  (js/__karma__.complete #js {"coverage" (aget js/window "__coverage__")}))

(defn start []
  ;; (js/console.log "test env" @st/tests-ref)
  (js/__karma__.info #js {:total (env/get-test-count)})
  (let [env (ct/empty-env ::karma)]
    (st/run-all-tests env)))

(defn stop [done]
  (throw (ex-info "karma doesn't support live reload for now!" {})))

;; not sure we need to do something once?
(defn ^:export init []
  (start))
