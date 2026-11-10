(ns demo.foo-test
  "dummy tests showing what the :browser-test UI can display, many fail on purpose"
  (:require
    [cljs.test :refer (deftest is are testing async use-fixtures)]))

;; output from fixtures shows up in the console, output inside tests is captured per test
(use-fixtures :each
  {:before (fn [] (println "fixture: before each"))
   :after (fn [] (println "fixture: after each"))})

(defrecord User [name email])

;; passing

(deftest foo
  (is (= 1 1)))

(deftest passing-many-assertions
  (doseq [i (range 20)]
    (is (= i (dec (inc i))))))

(deftest passing-with-output
  (println "output is captured per test")
  (prn {:and "printed data" :too [1 2 3]})
  (is true))

(deftest no-assertions
  ;; passes, but the UI points out that nothing was checked
  )

(deftest slow-async-pass
  (async done
    (js/setTimeout
      (fn []
        (is (= :done :done))
        (done))
      350)))

;; equality diffs

(deftest map-diff
  (is (= {:id 1 :name "foo" :email "foo@example.com" :roles #{:admin :user}}
         {:id 1 :name "bar" :roles #{:user :guest} :created-at "2026-11-10"})))

(deftest nested-diff
  (is (= {:user {:name "foo" :settings {:theme "dark" :lang "en" :tz "UTC"}}
          :items [{:id 1 :qty 2} {:id 2 :qty 1}]}
         {:user {:name "foo" :settings {:theme "light" :lang "en"}}
          :items [{:id 1 :qty 3} {:id 2 :qty 1} {:id 3 :qty 1}]})))

(deftest sequence-diff
  (is (= [1 2 3 4 5] [1 2 :three 4]))
  (is (= '(:a :b) '(:a :b :c :d)))
  (is (= #{1 2 3} #{2 3 4})))

(deftest scalar-diff
  (is (= "hello world" "hello wrld"))
  (is (= 42 41))
  (is (= :keyword "keyword"))
  (is (= {:a 1} nil)))

(deftest record-diff
  (is (= (->User "foo" "foo@example.com") (->User "foo" "bar@example.com")))
  ;; same keys, but a record is never = to a plain map
  (is (= {:name "foo" :email "foo@example.com"} (->User "foo" "foo@example.com"))))

(deftest large-map-diff
  ;; more than 8 keys, keys are sorted for display
  (is (= (into {} (for [i (range 15)] [(keyword (str "key-" i)) {:index i :label (str "item " i)}]))
         (into {} (for [i (range 15)] [(keyword (str "key-" i)) {:index i :label (str "item " (if (= i 9) "nine" i))}])))))

(deftest long-collections
  ;; short items fill lines, mismatches are never truncated
  (is (= (vec (range 300)) (vec (concat (range 100) [:x] (range 101 301)))))
  ;; other values are truncated after 250 items
  (is (empty? (range 1000))))

;; other assertion kinds

(deftest predicate-failures
  (is (even? 3))
  (is (< 3 2 1))
  (is (nil? {:a 1}))
  (is (contains? #{:a :b} :c)))

(deftest truthiness-failure
  (let [result (seq [])]
    (is result "expected a non-empty result")))

(defn fail! [msg]
  (throw (js/Error. msg)))

(deftest thrown-failures
  (is (thrown? js/Error (+ 1 1)))
  (is (thrown-with-msg? js/Error #"expected message" (fail! "actual message"))))

(deftest are-failures
  (are [x y] (= x (* 2 y))
    2 1
    4 2
    5 2
    8 3))

(deftest mixed-pass-and-fail
  (is (= 1 1))
  (is (= 2 3))
  (is (= :a :a))
  (is (= [1] [2]))
  (is (string? "yes")))

;; messages and contexts

(deftest messages-and-contexts
  (testing "user signup"
    (testing "with an invalid email"
      (is (= {:valid? false :errors [:email]}
             {:valid? true :errors []})
          "invalid emails must be rejected")))

  (testing "a multi-line message"
    (is (= 1 2) "first line of the message\nsecond line with more detail")))

;; errors

(deftest error-ex-info
  (is (= 1 1) "this assertion still counts")
  (throw (ex-info "failed to load user" {:user-id 123 :status 404})))

(deftest error-with-cause
  (throw (ex-info "request failed" {:url "/api/users"}
           (ex-info "connection refused" {:port 8080}))))

(deftest error-js-error
  (let [obj nil]
    ;; a plain JS TypeError
    (.toUpperCase obj)))

(deftest error-in-assertion
  (is (= 1 (throw (js/Error. "thrown while evaluating the assertion")))))

(deftest error-non-error-thrown
  (throw {:not "an error object"}))

;; async

(deftest async-failure
  (async done
    (js/setTimeout
      (fn []
        (is (= :expected :actual) "checked in a callback")
        (done))
      50)))

(deftest async-throws-in-callback
  ;; escapes cljs.test, attributed to this test by the runner, then hits the timeout
  (async done
    (js/setTimeout
      (fn []
        (fail! "thrown in a setTimeout callback")
        (done))
      10)))

(deftest async-never-done
  ;; aborted after shadow.test.runner/ASYNC-TIMEOUT
  (async done
    (is true "this one never calls done")))
