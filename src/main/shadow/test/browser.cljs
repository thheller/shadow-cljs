(ns shadow.test.browser
  "generic browser test runner, used by the :browser-test target.

   runs all tests on load. after a hot-reload only the tests affected by the change run again.

   for automated/headless use:
   - results are printed to the console in a cljs.test like format
   - <html data-test-status=\"running|passed|failed\"> and document.title reflect the status
   - window.shadowTest provides access to the results:
       await shadowTest.done()       ;; resolves with the JSON report once no run is active
       shadowTest.report()           ;; JSON report of all tests, failures include the text output
       shadowTest.text()             ;; plain text report, same as the console output
       await shadowTest.run(filter?) ;; runs tests matching filter (comma separated), all if omitted
       shadowTest.list()             ;; all test ids
   - url params: ?test=some.ns,some.ns/some-test limits tests, ?filter=text sets the filter"
  {:dev/always true}
  (:require
    [clojure.string :as str]
    [cljs.reader :as reader]
    [shadow.grove :as sg]
    [shadow.test.env :as env]
    [shadow.test.runner :as runner]
    [shadow.test.browser.ui :as ui]))

(defonce rt-ref (sg/get-runtime ::ui))

(defonce started-ref (atom false))

;; persisted user selection, per test page

(defn prefs-key []
  (str "shadow.test.browser/prefs:" js/location.pathname))

(defn load-prefs! []
  (try
    (when-some [s (js/localStorage.getItem (prefs-key))]
      (let [{:keys [filter excluded autorun only-failures]} (reader/read-string s)]
        (swap! runner/state-ref update :prefs merge
          (cond-> {}
            (string? filter) (assoc :filter filter)
            (set? excluded) (assoc :excluded excluded)
            (boolean? autorun) (assoc :autorun autorun)
            (boolean? only-failures) (assoc :only-failures only-failures)))))
    (catch :default e
      ;; storage may be unavailable or contain garbage, never worth failing over
      nil)))

(defn save-prefs! [prefs]
  (try
    (js/localStorage.setItem (prefs-key) (pr-str prefs))
    (catch :default e
      nil)))

(defn parse-test-syms [s]
  (->> (str/split s #",")
       (map str/trim)
       (remove str/blank?)
       (map symbol)
       (into #{})))

(defn apply-url-params! []
  (let [params (js/URLSearchParams. js/location.search)]
    (when-some [filter (.get params "filter")]
      (swap! runner/state-ref assoc-in [:prefs :filter] filter))
    (when-some [test (.get params "test")]
      (let [syms (parse-test-syms test)]
        (when (seq syms)
          (swap! runner/state-ref assoc :only syms))))))

(defn sync-url! [{:keys [prefs only]}]
  ;; keeps the url shareable and reload safe, but only touches params that were used
  (let [url (js/URL. js/location.href)
        params (.-searchParams url)
        filter (:filter prefs)]
    (cond
      (not (str/blank? filter))
      (.set params "filter" filter)

      (.has params "filter")
      (.delete params "filter"))
    (when (and (nil? only) (.has params "test"))
      (.delete params "test"))
    (let [href (.toString url)]
      (when (not= href js/location.href)
        (js/history.replaceState nil "" href)))))

;; status for automated tools

(defn update-document-status! [state]
  (let [status (runner/overall-status state)
        {:keys [pass fail error tests]} (runner/summarize state (runner/all-test-ids state))
        {:keys [completed total]} (:run state)]
    (.setAttribute js/document.documentElement "data-test-status" (name status))
    (set! js/document.title
      (str (case status
             :running (str "⏳ " completed "/" total)
             :failed (str "✕ " (+ fail error) " failed")
             :passed (str "✓ " pass "/" tests " passed")
             "Tests")
           " – Tests"))))

(defn on-state-change [_ _ old new]
  (when (not= (:prefs old) (:prefs new))
    (save-prefs! (:prefs new)))

  (when (or (not= (:prefs old) (:prefs new))
            (not= (:only old) (:only new)))
    (sync-url! new))

  (when (or (not (identical? (:run old) (:run new)))
            (not (identical? (:results old) (:results new)))
            (not (identical? (:tests old) (:tests new))))
    (update-document-status! new))

  (let [old-run (:run old)
        new-run (:run new)]
    (when (and (= :running (:status old-run))
               (contains? #{:done :cancelled} (:status new-run))
               (= (:id old-run) (:id new-run)))
      (runner/print-run-report! new))))

;; headless API

(defn done-promise []
  (js/Promise.
    (fn [resolve reject]
      (runner/when-idle
        #(resolve (clj->js (runner/export)))))))

(defn install-api! []
  (unchecked-set js/goog.global "shadowTest"
    #js {:done
         done-promise

         :report
         (fn []
           (clj->js (runner/export)))

         :text
         (fn []
           (runner/text-report))

         :status
         (fn []
           (name (runner/overall-status @runner/state-ref)))

         :list
         (fn []
           (clj->js (map str (runner/all-test-ids @runner/state-ref))))

         :run
         (fn [filter]
           (let [state @runner/state-ref]
             (runner/run-tests!
               (if (string? filter)
                 (runner/tests-matching state filter)
                 (runner/all-test-ids state))
               {:reason :api})
             (done-promise)))}))

;; lifecycle

(defn root-el []
  (or (js/document.getElementById "test-root")
      (let [el (js/document.createElement "div")]
        (set! (.-id el) "test-root")
        (js/document.body.appendChild el)
        el)))

(defn render! []
  (ui/install-css!)
  (sg/render rt-ref (root-el) (ui/ui-root)))

(defn start []
  (runner/update-tests! (env/get-test-data))
  (render!)

  (let [state @runner/state-ref]
    (if-not @started-ref
      (do (reset! started-ref true)
          (runner/run-tests! (runner/tests-in-scope state) {:reason :load}))

      ;; hot-reload, only run what is affected by the change
      (when (get-in state [:prefs :autorun])
        (let [ids (filterv #(runner/needs-run? state %) (runner/tests-in-scope state))]
          (when (seq ids)
            (runner/run-tests! ids {:reason :reload})))))))

(defn stop [done]
  ;; don't reload code while tests are running, let the current namespace finish
  ;; an async test that never completes gets aborted after a while
  (if-not (runner/running?)
    (done)
    (let [run-id (runner/active-run-id)
          called-ref (atom false)

          timeout
          (js/setTimeout
            (fn []
              (when (= run-id (runner/active-run-id))
                (js/console.warn "shadow.test.browser: tests did not finish in time, reloading anyways.")
                (runner/abandon!)))
            5000)]

      (runner/cancel!)
      (runner/when-idle
        (fn []
          (js/clearTimeout timeout)
          (when (compare-and-set! called-ref false true)
            (done)))))))

(defn focus-search! [^js e]
  (when (and (= "/" (.-key e))
             (not (contains? #{"INPUT" "TEXTAREA"} (.. e -target -tagName))))
    (when-some [el (js/document.getElementById "st-search")]
      (.preventDefault e)
      (.focus el))))

(defn ^:export init []
  (load-prefs!)
  (apply-url-params!)
  (add-watch runner/state-ref ::browser on-state-change)
  (js/document.addEventListener "keydown" focus-search!)
  ;; errors thrown in async callbacks escape cljs.test, attribute them to the running test
  (js/window.addEventListener "error" #(runner/report-uncaught! (or (.-error ^js %) (.-message ^js %))))
  (js/window.addEventListener "unhandledrejection" #(runner/report-uncaught! (.-reason ^js %)))
  (install-api!)
  (start))
