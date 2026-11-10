(ns shadow.test.runner
  "test runner state without any UI.

   runs tests one namespace at a time and records all cljs.test reports as data.
   the browser UI renders this state, the headless API exports it as JSON/text.

   (run-tests! ids) runs tests, ids are fully qualified test symbols eg. demo.foo-test/some-test"
  (:require
    [clojure.string :as str]
    [cljs.test :as ct]
    [shadow.test :as st]
    [shadow.test.env :as env]
    [shadow.test.diff :as diff]))

;; cljs.test has no timeouts, an async test that never calls done would stop the run forever
;; configurable via :closure-defines {shadow.test.runner/ASYNC-TIMEOUT 60000}, 0 to disable
(goog-define ASYNC-TIMEOUT 30000)

(defonce state-ref
  (atom {;; ns-sym -> {:ns ns-sym :fingerprint n :tests [test-id ...]}
         :namespaces {}
         :ns-order []
         ;; test-id -> {:id test-id :ns ns-sym :name sym :file :line :var the-var}
         :tests {}
         ;; test-id -> {:status :pass|:fail|:error|:queued|:running ...}
         :results {}
         ;; ns-sym -> [report ...], reports not happening in any test (eg. in fixtures)
         :ns-reports {}
         ;; current or last run
         :run nil
         :run-count 0
         ;; user selection, persisted by the browser runner
         :prefs {:filter "" :excluded #{} :autorun true :only-failures false}
         ;; optional set of ns or test symbols to limit tests to, eg. from ?test= url param
         :only nil
         ;; ui only state
         :ui {:expanded {}}}))

(defonce default-report ct/report)

;; mutable state of the active run, not exposed to the UI
(defonce active-ref (volatile! nil))

(defonce idle-callbacks-ref (volatile! []))

(defn now []
  (js/performance.now))

(defn test-id [the-var]
  (let [{:keys [ns name]} (meta the-var)]
    (symbol (str ns) (str name))))

(defn running? []
  (some? @active-ref))

(defn active-run-id []
  (:run-id @active-ref))

;; tests

(defn update-tests!
  "takes the result of (shadow.test.env/get-test-data), called on load and after every hot-reload"
  [test-data]
  (env/reset-test-data! test-data)
  (swap! state-ref
    (fn [state]
      (let [ns-order
            (->> test-data
                 (keep (fn [[ns {:keys [vars]}]]
                         (when (seq vars)
                           ns)))
                 (sort)
                 (vec))

            tests
            (into {}
              (for [ns ns-order
                    the-var (get-in test-data [ns :vars])
                    :let [{:keys [name file line column]} (meta the-var)
                          id (test-id the-var)]]
                [id {:id id
                     :ns ns
                     :name name
                     :file file
                     :line line
                     :column column
                     :var the-var}]))

            namespaces
            (into {}
              (for [ns ns-order
                    :let [{:keys [vars fingerprint]} (get test-data ns)]]
                [ns {:ns ns
                     :fingerprint fingerprint
                     :tests (mapv test-id vars)}]))]

        (-> state
            (assoc :ns-order ns-order
                   :tests tests
                   :namespaces namespaces)
            (update :results select-keys (keys tests))
            (update :ns-reports select-keys ns-order))))))

(defn outdated?
  "true if the test has a result, but the code it depends on changed since it ran"
  [state id]
  (let [{:keys [ns var]} (get-in state [:tests id])
        fingerprint (get-in state [:namespaces ns :fingerprint])
        {:keys [status] :as result} (get-in state [:results id])]
    (cond
      (or (nil? result) (= :queued status) (= :running status))
      false

      (some? fingerprint)
      (not= fingerprint (:fingerprint result))

      ;; no fingerprints, can only tell if the test itself was reloaded
      :else
      (not (identical? (:test (meta var)) (:test-fn result))))))

(defn needs-run? [state id]
  (let [result (get-in state [:results id])]
    (or (nil? result)
        (outdated? state id))))

(defn failed? [state id]
  (contains? #{:fail :error} (get-in state [:results id :status])))

(defn status-of
  ":pass :fail :error :queued :running :outdated or :not-run"
  [state id]
  (let [status (get-in state [:results id :status])]
    (cond
      (nil? status) :not-run
      (outdated? state id) :outdated
      :else status)))

;; selection

(defn parse-filter
  "comma separated alternatives, each matching as a case insensitive substring of ns/name"
  [s]
  (->> (str/split (or s "") #",")
       (map str/trim)
       (remove str/blank?)
       (map str/lower-case)
       (vec)))

(defn filter-pred
  "returns a predicate for test ids matching the filter and :only, ignores exclusions"
  [{:keys [prefs only] :as state}]
  (let [alternatives (parse-filter (:filter prefs))]
    (fn [id]
      (and (or (nil? only)
               (contains? only id)
               (contains? only (symbol (namespace id))))
           (or (empty? alternatives)
               (let [s (str/lower-case (str id))]
                 (some #(str/includes? s %) alternatives)))))))

(defn scope-pred
  "tests that match the filter and are not excluded, the tests the Run button would run"
  [{:keys [prefs] :as state}]
  (let [pred (filter-pred state)
        excluded (:excluded prefs)]
    (fn [id]
      (and (not (contains? excluded id))
           (pred id)))))

(defn all-test-ids [{:keys [ns-order namespaces] :as state}]
  (into [] (mapcat #(get-in namespaces [% :tests])) ns-order))

(defn tests-in-scope [state]
  (filterv (scope-pred state) (all-test-ids state)))

(defn tests-matching
  "all tests matching a filter string, ignoring the user selection"
  [state filter-str]
  (filterv (filter-pred (assoc-in state [:prefs :filter] filter-str)) (all-test-ids state)))

;; summaries

(defn summarize [state ids]
  (reduce
    (fn [acc id]
      (let [{:keys [pass fail error duration] :as result} (get-in state [:results id])
            status (status-of state id)]
        (-> acc
            (update :tests inc)
            (update status inc)
            (update :assertions + (or pass 0) (or fail 0) (or error 0))
            ;; assertion level counts, cljs.test reports these
            (update :failures + (or fail 0))
            (update :errors + (or error 0))
            (update :duration + (or duration 0)))))
    {:tests 0 :pass 0 :fail 0 :error 0 :queued 0 :running 0 :outdated 0 :not-run 0
     :assertions 0 :failures 0 :errors 0 :duration 0}
    ids))

(defn overall-status
  ":running, :failed, :passed or :idle (nothing ran yet)"
  [state]
  (let [{:keys [run results]} state]
    (cond
      (= :running (:status run))
      :running

      (or (some #(contains? #{:fail :error} (:status %)) (vals results))
          (some seq (vals (:ns-reports state))))
      :failed

      (seq results)
      :passed

      :else
      :idle)))

;; running

(defn- restore-print! []
  (when-some [[out err] (:orig-print @active-ref)]
    (set! *print-fn* out)
    (set! *print-err-fn* err)
    (vswap! active-ref dissoc :orig-print)))

(defn- capture-print! [s]
  (when (:test @active-ref)
    (vswap! active-ref update-in [:acc :out] conj (if *print-newline* s (str s "\n")))))

(defn- install-print-capture! []
  (when-not (:orig-print @active-ref)
    (let [out *print-fn*
          err *print-err-fn*]
      (vswap! active-ref assoc :orig-print [out err])
      (set! *print-fn*
        (fn [s]
          (capture-print! s)
          (if out (out s) (js/console.log s))))
      (set! *print-err-fn*
        (fn [s]
          (capture-print! s)
          (if err (err s) (js/console.error s)))))))

(defn- begin-test! [run-id {the-var :var :as m}]
  (let [id (test-id the-var)]
    (vswap! active-ref assoc
      :test id
      :acc {:pass 0 :fail 0 :error 0 :reports [] :out [] :started (now)})
    (swap! state-ref
      (fn [state]
        (-> state
            (assoc-in [:results id :status] :running)
            (assoc-in [:run :current-test] id))))))

(defn- end-test! [run-id {the-var :var :as m}]
  (let [{:keys [ns test acc]} @active-ref
        {:keys [pass fail error reports out started]} acc]
    (when test
      (vswap! active-ref assoc :test nil :acc nil)
      (swap! state-ref
        (fn [state]
          (-> state
              (assoc-in [:results test]
                {:id test
                 :status (cond (pos? error) :error (pos? fail) :fail :else :pass)
                 :pass pass
                 :fail fail
                 :error error
                 :reports reports
                 :out (apply str out)
                 :duration (- (now) started)
                 :fingerprint (get-in state [:namespaces ns :fingerprint])
                 :test-fn (:test (meta the-var))
                 :run-id run-id})
              (update-in [:run :completed] inc)
              (assoc-in [:run :current-test] nil)))))))

(defn- add-report! [{:keys [type] :as m}]
  (ct/inc-report-counter! type)
  (let [{:keys [ns test]} @active-ref
        report
        (-> m
            (select-keys [:type :message :expected :actual :markup :file :line :column])
            (assoc :contexts (vec (reverse (:testing-contexts (ct/get-current-env))))))]
    (if test
      (vswap! active-ref update-in [:acc :reports] conj report)
      ;; reports outside any test, eg. exceptions or assertions in fixtures
      (swap! state-ref update-in [:ns-reports ns] (fnil conj []) report))))

(defn report-uncaught!
  "records an error that escaped the test, eg. thrown in a callback of an async test.
   the test will still only finish when it calls done or times out.
   returns true if the error was attributed to a test"
  [e]
  (when (:test @active-ref)
    (vswap! active-ref update :acc
      (fn [acc]
        (-> acc
            (update :error inc)
            (update :reports conj
              {:type :error
               :message "Uncaught exception, thrown outside the test body (eg. in an async callback)."
               :expected nil
               :actual e
               :contexts []}))))
    true))

(declare run-next! start-run!)

(defn- handle-report! [run-id {:keys [type] :as m}]
  ;; ignore reports from abandoned runs
  (when (= run-id (:run-id @active-ref))
    (case type
      :begin-test-var
      (begin-test! run-id m)

      :pass
      (do (ct/inc-report-counter! :pass)
          (vswap! active-ref update-in [:acc :pass] inc))

      (:fail :error)
      (do (when (:test @active-ref)
            (vswap! active-ref update-in [:acc type] inc))
          (add-report! m))

      :end-test-var
      (end-test! run-id m)

      ;; ns completed, yield to let the UI update
      :end-run-tests
      (js/setTimeout #(run-next! run-id) 0)

      ;; :begin-test-ns, :end-test-ns, :begin-run-tests, :summary, custom report types
      nil)))

(defn- finish-run! [run-id status]
  (restore-print!)
  (let [{:keys [next]} @active-ref
        callbacks @idle-callbacks-ref]
    (vreset! active-ref nil)
    (vreset! idle-callbacks-ref [])

    (swap! state-ref
      (fn [state]
        (-> state
            (update :run assoc :status status :finished (now) :current-ns nil :current-test nil)
            ;; tests that didn't get to run in a cancelled run go back to their previous result
            (update :results
              (fn [results]
                (reduce-kv
                  (fn [results id {:keys [status prev]}]
                    (cond
                      (not (contains? #{:queued :running} status))
                      results

                      prev
                      (assoc results id prev)

                      :else
                      (dissoc results id)))
                  results
                  results))))))

    (if next
      ;; another run was requested while this one was still running
      (do (vreset! idle-callbacks-ref callbacks)
          (let [[ids opts] next]
            (start-run! ids opts)))
      (doseq [callback callbacks]
        (callback)))))

(defn- make-env [run-id]
  (-> (ct/empty-env)
      (assoc :report-fn #(handle-report! run-id %)
             ::st/async-timeout ASYNC-TIMEOUT)))

(defn- run-next! [run-id]
  (let [{:keys [queue cancel?] :as active} @active-ref]
    (when (= run-id (:run-id active))
      (if (or cancel? (empty? queue))
        (finish-run! run-id (if (and cancel? (seq queue)) :cancelled :done))
        (let [[ns vars] (first queue)]
          (vswap! active-ref assoc :queue (subvec queue 1) :ns ns :test nil :acc nil)
          (swap! state-ref assoc-in [:run :current-ns] ns)
          ;; installed here and not in start-run! since that may be called from within a REPL
          ;; eval with *print-fn* bound, which would undo this once the binding ends
          (install-print-capture!)
          (try
            (st/run-test-vars (make-env run-id) vars)
            (catch :default e
              ;; an exception outside of tests (eg. in a :once fixture) aborts the cljs.test run
              ;; since it never reaches :end-run-tests the cleanup has to happen here
              (set! ct/report default-report)
              (when-some [test (:test @active-ref)]
                (end-test! run-id {:var (get-in @state-ref [:tests test :var])}))
              (swap! state-ref
                (fn [state]
                  (-> state
                      (update-in [:ns-reports ns] (fnil conj [])
                        {:type :error
                         :message "Uncaught exception, not in a test. Tests in this namespace were aborted."
                         :expected nil
                         :actual e
                         :contexts []})
                      ;; tests that never ran
                      (update :results
                        (fn [results]
                          (reduce
                            (fn [results the-var]
                              (let [id (test-id the-var)]
                                (if (contains? #{:queued :running} (get-in results [id :status]))
                                  (dissoc results id)
                                  results)))
                            results
                            vars))))))
              (js/setTimeout #(run-next! run-id) 0))))))))

(defn start-run! [ids opts]
  (let [state @state-ref
        run-id (inc (:run-count state))
        id-set (set ids)

        queue
        (->> (:ns-order state)
             (keep (fn [ns]
                     (let [ns-ids (filterv id-set (get-in state [:namespaces ns :tests]))]
                       (when (seq ns-ids)
                         [ns (mapv #(get-in state [:tests % :var]) ns-ids)]))))
             (vec))

        ordered-ids
        (into [] (mapcat (fn [[ns vars]] (map test-id vars))) queue)]

    (vreset! active-ref {:run-id run-id :queue queue})

    (swap! state-ref
      (fn [state]
        (-> state
            (assoc :run-count run-id)
            (assoc :run {:id run-id
                         :status :running
                         :reason (:reason opts :manual)
                         :test-ids ordered-ids
                         :total (count ordered-ids)
                         :completed 0
                         :started (now)})
            (update :results
              (fn [results]
                (reduce
                  (fn [results id]
                    (let [prev (get results id)]
                      (assoc results id
                        {:id id
                         :status :queued
                         :prev (if (contains? #{:queued :running} (:status prev))
                                 (:prev prev)
                                 prev)})))
                  results
                  ordered-ids)))
            (update :ns-reports #(apply dissoc % (map first queue))))))

    (js/setTimeout #(run-next! run-id) 0)
    run-id))

(defn run-tests!
  "runs the given test ids, a run already in progress is cancelled once its current namespace completes
   opts can contain :reason (:manual, :load, :reload) which is only informational"
  ([ids]
   (run-tests! ids {}))
  ([ids opts]
   (let [ids (filterv #(contains? (:tests @state-ref) %) ids)]
     (if (running?)
       (vswap! active-ref assoc :cancel? true :next [ids opts])
       (start-run! ids opts)))))

(defn cancel!
  "cancels the active run once the current namespace completes"
  []
  (when (running?)
    (vswap! active-ref assoc :cancel? true)
    (vswap! active-ref dissoc :next)))

(defn when-idle
  "calls callback once no run is active"
  [callback]
  (if (running?)
    (vswap! idle-callbacks-ref conj callback)
    (callback)))

(defn abandon!
  "stops the active run immediately, eg. when an async test refuses to complete"
  []
  (when-some [{:keys [run-id]} @active-ref]
    (set! ct/report default-report)
    (vswap! active-ref dissoc :next)
    (finish-run! run-id :cancelled)))

;; text output

(defn source-file? [file]
  (and (string? file)
       (or (str/ends-with? file ".cljs")
           (str/ends-with? file ".cljc"))))

(defn test-location [state id]
  (let [{:keys [file line]} (get-in state [:tests id])]
    (when file
      (str file (when line (str ":" line))))))

(defn error-info
  "data about a thrown value"
  [e]
  {:message
   (cond
     (instance? js/Error e) (str (.-name e) ": " (.-message e))
     :else (pr-str e))
   :data (ex-data e)
   :cause (ex-cause e)
   :stack
   (when (instance? js/Error e)
     (let [stack (.-stack e)]
       (when (string? stack)
         stack)))})

(def runtime-frame-re
  ;; frames from the test machinery, not useful for finding the problem
  #"/(cljs\.core|cljs\.test|shadow\.test|shadow\.test\.runner|goog\.[^/]*)\.js[:?]")

(defn stack-frames
  "the relevant stack frames of an error, without the test runner frames"
  [e]
  (when-some [stack (:stack (error-info e))]
    (->> (str/split-lines stack)
         (map str/trim)
         ;; chrome "at fn (url:1:2)", firefox/safari "fn@url:1:2"
         (filter #(re-find #"(^at |@).*:\d+" %))
         ;; everything after the runner calling the test is noise
         (take-while #(not (re-find #"/shadow\.test\.js[:?]" %)))
         (remove #(re-find runtime-frame-re %))
         (take 10))))

(defn- indent-lines [s n]
  (str/replace s #"\n" (str "\n" (.repeat " " n))))

(defn- label-value [label x]
  ;; "expected: " is 10 chars, values continue aligned after the label
  (str (.padStart label 8) ": " (diff/pp-str x 10) "\n"))

(defn report-sections
  "the parts of a report shown to the user, each [label value-or-text kind]
   shared by the text and UI renderers so they show the same thing"
  [{:keys [type expected actual] :as report}]
  (if (= :error type)
    (let [{:keys [message data cause]} (error-info actual)]
      (cond-> []
        ;; exception thrown inside (is ...), expected is the assertion form
        (some? expected)
        (conj [:expected expected :value])

        :always
        (conj [:error message :text])

        (some? data)
        (conj [:data data :value])

        (some? cause)
        (conj [:cause (:message (error-info cause)) :text])))

    ;; for (is (= ...)) and (is (match? ...)) actual is the diff, showing the full actual
    ;; value with all mismatches marked. same notation as matcher-combinators.
    (if-some [diff (diff/report-diff report)]
      [[:expected expected :value]
       [:actual diff :value]]
      [[:expected expected :value]
       [:actual actual :value]])))

(defn report->text [state test-id {:keys [type message contexts file line actual] :as report}]
  (let [location (or (when (source-file? file)
                       (str file (when line (str ":" line))))
                     (when test-id
                       (test-location state test-id)))]
    (str (if (= :error type) "ERROR" "FAIL")
         " in " (or test-id "fixture")
         (when location (str " (" location ")"))
         "\n"
         (when (seq contexts)
           (str " testing: " (str/join " " contexts) "\n"))
         (when message
           (str " message: " (indent-lines (str message) 10) "\n"))
         (->> (report-sections report)
              (map (fn [[label x kind]]
                     (if (= kind :text)
                       (str (.padStart (name label) 8) ": " (indent-lines (str x) 10) "\n")
                       (label-value (name label) x))))
              (apply str))
         (when (= :error type)
           (when-some [frames (seq (stack-frames actual))]
             (str "   stack: " (str/join "\n          " frames) "\n"))))))

(defn summary-text
  "same format as cljs.test, so tools looking for it keep working"
  [{:keys [tests assertions failures errors]}]
  (str "Ran " tests " tests containing " assertions " assertions.\n"
       failures " failures, " errors " errors."))

(defn failure-reports
  "[test-id report] for all failures, in test order. test-id is nil for ns level reports"
  [state ids]
  (let [id-set (set ids)]
    (concat
      (for [ns (:ns-order state)
            report (get-in state [:ns-reports ns])
            :when (some #(= ns (symbol (namespace %))) ids)]
        [nil report])
      (for [id (all-test-ids state)
            :when (contains? id-set id)
            report (get-in state [:results id :reports])]
        [id report]))))

(defn text-report
  "plain text report of the current state of all tests, or the given test ids"
  ([]
   (text-report @state-ref))
  ([state]
   (text-report state (all-test-ids state)))
  ([state ids]
   (let [summary (summarize state ids)
         failures (failure-reports state ids)
         not-run (+ (:not-run summary) (:outdated summary))]
     (str (->> failures
               (map (fn [[id report]]
                      (report->text state id report)))
               (str/join "\n"))
          (when (seq failures) "\n")
          (summary-text (update summary :tests - not-run))
          (when (pos? not-run)
            (str "\n" not-run " tests did not run or are outdated."))))))

;; JSON export, for headless use

(defn- report->json [state test-id {:keys [type message contexts] :as report}]
  (let [text-of (fn [x] (diff/pp-str x))]
    (into
      {:type (name type)
       :message message
       :testing contexts
       :text (report->text state test-id report)}
      (map (fn [[label x kind]]
             [label (if (= :text kind) (str x) (text-of x))]))
      (report-sections report))))

(defn export
  "the current state as JSON compatible data"
  ([]
   (export @state-ref))
  ([state]
   (let [ids (all-test-ids state)
         summary (summarize state ids)
         {:keys [run]} state]
     {:status (name (overall-status state))
      :summary
      {:tests (:tests summary)
       :passed (:pass summary)
       :failed (:fail summary)
       :errors (:error summary)
       :notRun (+ (:not-run summary) (:queued summary) (:running summary))
       :outdated (:outdated summary)
       :assertions (:assertions summary)}
      :run
      (when run
        {:id (:id run)
         :status (name (:status run))
         :reason (some-> (:reason run) name)
         :testIds (mapv str (:test-ids run))
         :completed (:completed run)
         :durationMs
         (when-some [finished (:finished run)]
           (js/Math.round (- finished (:started run))))})
      :namespaceErrors
      (vec (for [ns (:ns-order state)
                 report (get-in state [:ns-reports ns])]
             (assoc (report->json state nil report) :ns (str ns))))
      :tests
      (vec (for [id ids
                 :let [{:keys [ns name file line]} (get-in state [:tests id])
                       {:keys [pass fail error duration out reports]} (get-in state [:results id])]]
             {:id (str id)
              :ns (str ns)
              :name (str name)
              :file file
              :line line
              :status (cljs.core/name (status-of state id))
              :durationMs (when duration (js/Math.round duration))
              :assertions {:pass (or pass 0) :fail (or fail 0) :error (or error 0)}
              :out (when (seq out) out)
              :failures (mapv #(report->json state id %) reports)}))})))

(defn print-run-report!
  "prints the result of the last run to the console"
  [state]
  (let [{:keys [test-ids status reason started finished]} (:run state)
        summary (summarize state test-ids)
        ;; tests of a cancelled run that didn't get to run
        summary (update summary :tests - (:not-run summary) (:queued summary) (:running summary))
        failures (failure-reports state test-ids)]

    (doseq [[id {:keys [type actual] :as report}] failures]
      (if (and (= :error type) (instance? js/Error actual))
        ;; console shows source mapped stack traces for the actual error object
        (js/console.error (report->text state id report) actual)
        (js/console.error (report->text state id report))))

    (let [text
          (str (summary-text summary)
               (when (= :cancelled status)
                 "\nRun cancelled, not all tests completed.")
               (let [all (all-test-ids state)]
                 (when (< (count test-ids) (count all))
                   (let [{:keys [fail error]} (summarize state all)]
                     (str "\n" (count test-ids) " of " (count all) " tests ran"
                          (when (= :reload reason) " (affected by reload)")
                          ". Overall: " fail " failed tests, " error " tests with errors.")))))]

      (if (or (seq failures) (= :cancelled status))
        (js/console.warn text)
        (js/console.log text)))))
