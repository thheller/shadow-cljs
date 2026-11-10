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

;; status in the browser tab and for automated tools

(defn favicon-uri [status]
  (let [[color badge]
        (case status
          :running ["#2563eb" "<circle cx='6' cy='6' r='3.5'/><path d='M6 3.5V6l2 1.2'/>"]
          :passed ["#15803d" "<path d='m2.5 6 2.5 2.5 4.5-5'/>"]
          :failed ["#dc2626" "<path d='m3.5 3.5 5 5m0-5-5 5'/>"]
          ["#64748b" "<path d='M3 6h6'/>"])
        svg
        (str "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
             "<rect width='32' height='32' rx='7' fill='#0f172a'/>"
             ;; Keep the flask silhouette in every state so the tab is recognizable.
             "<path d='M8 19h11l4 7H4z' fill='" color "'/>"
             "<path d='M9 3h9m-7 0v9L4 25q-1 3 2 3h15q3 0 2-3l-7-13V3'"
             " fill='none' stroke='white' stroke-width='2.5' stroke-linecap='round' stroke-linejoin='round'/>"
             "<circle cx='25' cy='24' r='7' fill='" color "' stroke='#0f172a' stroke-width='2'/>"
             "<g transform='translate(19 18)' fill='none' stroke='white' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>"
             badge "</g></svg>")]
    (str "data:image/svg+xml," (js/encodeURIComponent svg))))

(defn update-favicon! [status]
  (let [el (or (js/document.getElementById "shadow-test-favicon")
               (let [el (js/document.createElement "link")]
                 (set! (.-id el) "shadow-test-favicon")
                 (set! (.-rel el) "icon")
                 (set! (.-type el) "image/svg+xml")
                 (.setAttribute el "sizes" "any")
                 (.appendChild js/document.head el)
                 el))
        href (favicon-uri status)]
    ;; A run updates on every assertion. Only replace the icon when its status changes.
    (when (not= href (.-href el))
      (set! (.-href el) href)
      (.setAttribute el "data-test-status" (name status)))))

(defn update-document-status! [state]
  (let [status (runner/overall-status state)
        {:keys [pass fail error tests]} (runner/summarize state (runner/all-test-ids state))
        {:keys [completed total]} (:run state)]
    (.setAttribute js/document.documentElement "data-test-status" (name status))
    (update-favicon! status)
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
