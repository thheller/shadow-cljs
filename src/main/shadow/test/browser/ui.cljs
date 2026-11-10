(ns shadow.test.browser.ui
  "shadow-grove UI for the browser test runner. renders shadow.test.runner/state-ref.

   the markup is meant to be useful for automated tools too. every test and namespace
   carries data-status and data-test-id/data-ns attributes, statuses also have text."
  (:require-macros [shadow.test.browser.ui :refer (generated-css)])
  (:require
    [clojure.string :as str]
    [shadow.css :refer (css)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.test.diff :as diff]
    [shadow.test.runner :as runner]))

;; colors as variables, so dark mode is just a different set of variables
(def base-css
  ".st-root {
  --st-bg: #ffffff;
  --st-bg-subtle: #f8fafc;
  --st-bg-hover: #f1f5f9;
  --st-border: #e2e8f0;
  --st-fg: #0f172a;
  --st-muted: #64748b;
  --st-accent: #2563eb;
  --st-accent-fg: #ffffff;
  --st-pass: #15803d;
  --st-fail: #dc2626;
  --st-error: #c2410c;
  --st-running: #2563eb;
  --st-outdated: #a16207;
  --st-idle: #94a3b8;
  --st-fail-bg: #fef2f2;
  --st-error-bg: #fff7ed;
  --st-code-bg: #f8fafc;
  --st-expected: #166534;
  --st-expected-bg: #dcfce7;
  --st-actual: #991b1b;
  --st-actual-bg: #fee2e2;
  color-scheme: light;
}
@media (prefers-color-scheme: dark) {
  .st-root {
    --st-bg: #0d1117;
    --st-bg-subtle: #161b22;
    --st-bg-hover: #1f2630;
    --st-border: #30363d;
    --st-fg: #e6edf3;
    --st-muted: #8b949e;
    --st-accent: #2f81f7;
    --st-accent-fg: #ffffff;
    --st-pass: #3fb950;
    --st-fail: #f85149;
    --st-error: #f0883e;
    --st-running: #58a6ff;
    --st-outdated: #d29922;
    --st-idle: #6e7681;
    --st-fail-bg: rgba(248, 81, 73, 0.08);
    --st-error-bg: rgba(240, 136, 62, 0.08);
    --st-code-bg: #161b22;
    --st-expected: #7ee787;
    --st-expected-bg: rgba(46, 160, 67, 0.18);
    --st-actual: #ffa198;
    --st-actual-bg: rgba(248, 81, 73, 0.18);
    color-scheme: dark;
  }
}
body { margin: 0; }
.st-root {
  min-height: 100vh;
  background: var(--st-bg);
  color: var(--st-fg);
  font: 14px/1.45 system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif;
}
.st-root *, .st-root *::before, .st-root *::after { box-sizing: border-box; }
.st-root pre, .st-root code { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.st-root button, .st-root input { font: inherit; color: inherit; }
.st-sr { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; border: 0; }
@keyframes st-pulse { 50% { opacity: 0.35; } }
")

(def css-text
  (str base-css "\n" (generated-css)))

(defn install-css! []
  (let [el (or (js/document.getElementById "shadow-test-css")
               (let [el (js/document.createElement "style")]
                 (set! (.-id el) "shadow-test-css")
                 (.appendChild js/document.head el)
                 el))]
    (when (not= css-text (.-textContent el))
      (set! (.-textContent el) css-text))))

;; helpers

(defn expanded? [state ns]
  (let [explicit (get-in state [:ui :expanded ns])]
    (if (some? explicit)
      explicit
      (boolean
        (or (not (str/blank? (get-in state [:prefs :filter])))
            (some? (:only state))
            (seq (get-in state [:ns-reports ns]))
            (some #(runner/failed? state %) (get-in state [:namespaces ns :tests])))))))

(defn format-duration [ms]
  (cond
    (nil? ms) nil
    (< ms 1) "<1ms"
    (< ms 1000) (str (js/Math.round ms) "ms")
    :else (str (.toFixed (/ ms 1000) 2) "s")))

(defn plural [n word]
  (str n " " word (when (not= 1 n) "s")))

(def status-labels
  {:pass "passed"
   :fail "failed"
   :error "error"
   :running "running"
   :queued "queued"
   :outdated "outdated"
   :not-run "not run"})

(def $icon
  (css :inline-flex :items-center :justify-center :shrink-0 :font-bold
    {:width "18px"
     :height "18px"
     :border-radius "9999px"
     :font-size "11px"
     :line-height "1"
     :color "#fff"}))

(def $icon-pass (css {:background "var(--st-pass)"}))
(def $icon-fail (css {:background "var(--st-fail)"}))
(def $icon-error (css {:background "var(--st-error)"}))
(def $icon-running (css {:background "var(--st-running)" :animation "st-pulse 1s ease-in-out infinite"}))
(def $icon-queued (css {:border "2px solid var(--st-running)" :color "var(--st-running)"}))
(def $icon-outdated (css {:background "var(--st-outdated)"}))
(def $icon-not-run (css {:border "2px solid var(--st-idle)" :color "var(--st-idle)"}))

(defn status-icon [status]
  (let [[glyph class]
        (case status
          :pass ["✓" $icon-pass]
          :fail ["✕" $icon-fail]
          :error ["!" $icon-error]
          :running ["" $icon-running]
          :queued ["" $icon-queued]
          :outdated ["↻" $icon-outdated]
          ["" $icon-not-run])]
    (<< [:span {:class [$icon class] :aria-hidden "true" :title (get status-labels status)} glyph]
        [:span {:class "st-sr"} (get status-labels status)])))

(def $run-btn
  (css :inline-flex :items-center :justify-center :shrink-0 :cursor-pointer
    {:width "26px"
     :height "22px"
     :border "1px solid transparent"
     :border-radius "4px"
     :background "transparent"
     :color "var(--st-muted)"
     :font-size "10px"
     :opacity "0"}
    [:hover {:border-color "var(--st-border)" :color "var(--st-accent)" :background "var(--st-bg)"}]
    [:focus-visible {:opacity "1"}]))

;; shows run buttons only on hover, keeps the list calm
(def $row-hover
  (css
    ["&:hover" {:background "var(--st-bg-hover)"}]
    ["&:hover button" {:opacity "1"}]))

(defn run-button [ev label]
  (<< [:button {:class $run-btn :type "button" :title label :aria-label label :on-click ev} "▶"]))

;; values

(def $mark-expected (css {:color "var(--st-expected)" :background "var(--st-expected-bg)" :border-radius "2px"}))
(def $mark-actual (css {:color "var(--st-actual)" :background "var(--st-actual-bg)" :border-radius "2px"}))

(defn render-segment [seg idx]
  (if (string? seg)
    seg
    (let [[kind text] seg]
      (<< [:span {:class (if (contains? #{:expected :missing} kind) $mark-expected $mark-actual)
                  :data-mark (name kind)}
           text]))))

(defn render-value [x]
  (sg/simple-seq
    (binding [diff/*width* 90]
      (diff/layout x))
    render-segment))

;; reports

(def $meta
  (css :text-xs :whitespace-nowrap {:color "var(--st-muted)" :font-variant-numeric "tabular-nums"}))

(def $report
  (css :my-2 :py-2 :px-3
    {:border-left "3px solid var(--st-fail)"
     :background "var(--st-fail-bg)"
     :border-radius "0 4px 4px 0"}))

(def $report-error
  (css {:border-left-color "var(--st-error)"
        :background "var(--st-error-bg)"}))

(def $report-head
  (css :flex :items-center :gap-2 :flex-wrap :text-xs))

(def $badge
  (css :font-bold :tracking-wide {:color "var(--st-fail)"}))

(def $badge-error
  (css {:color "var(--st-error)"}))

(def $contexts
  (css {:color "var(--st-fg)"}))

(def $message
  (css :mt-1 :whitespace-pre-wrap :font-medium))

(def $sections
  (css :mt-2
    {:display "grid"
     :grid-template-columns "max-content minmax(0, 1fr)"
     :column-gap "12px"
     :row-gap "4px"}))

(def $section-label
  (css :text-xs :text-right
    {:color "var(--st-muted)"
     :padding-top "2px"}))

(def $section-value
  (css :m-0 :overflow-x-auto :whitespace-pre))

(def $details
  (css :mt-2 :text-xs
    {:color "var(--st-muted)"}
    ["& > summary" :cursor-pointer :select-none]
    ["& > pre" :mt-1 :mb-0 :p-2 :overflow-x-auto {:background "var(--st-code-bg)" :border-radius "4px" :color "var(--st-fg)"}]))

(defn ui-section [[label x kind] idx]
  (<< [:div {:class $section-label} (name label)]
      [:pre {:class $section-value :data-section (name label)}
       (if (= :text kind)
         (str x)
         (render-value x))]))

(defn ui-report [{:keys [type message contexts actual file line] :as report} idx]
  (let [error? (= :error type)
        stack (when error?
                (let [frames (runner/stack-frames actual)]
                  (when (seq frames)
                    (str/join "\n" frames))))]
    (<< [:div {:class [$report (when error? $report-error)] :data-report-type (name type)}
         [:div {:class $report-head}
          [:span {:class [$badge (when error? $badge-error)]} (if error? "ERROR" "FAIL")]
          (when (seq contexts)
            (<< [:span {:class $contexts} (str/join " › " contexts)]))
          (when (and line (runner/source-file? file))
            (<< [:span {:class $meta} (str file ":" line)]))]
         (when message
           (<< [:div {:class $message} (str message)]))
         [:div {:class $sections}
          (sg/simple-seq (runner/report-sections report) ui-section)]
         (when stack
           (<< [:details {:class $details}
                [:summary "Stack trace"]
                [:pre stack]]))])))

;; tests

(def $test-row
  (css :flex :items-center :gap-2 :py-1
    {:padding-left "52px"
     :padding-right "12px"
     :min-height "30px"}))

(def $test-name
  (css :truncate :font-mono {:font-size "13px"}))

(def $spacer
  (css :flex-1))

(def $test-body
  (css :pb-2 {:padding-left "78px" :padding-right "12px"}))

(defn assertion-text [{:keys [pass fail error]}]
  (let [n (+ pass fail error)]
    (if (zero? n)
      "no assertions"
      (plural n "assertion"))))

(defc ui-test [id]
  (bind info (sg/watch runner/state-ref [:tests id]))
  (bind result (sg/watch runner/state-ref [:results id]))
  (bind status (sg/watch runner/state-ref #(runner/status-of % id)))
  (bind excluded? (sg/watch runner/state-ref #(contains? (get-in % [:prefs :excluded]) id)))

  (render
    (let [{:keys [reports out duration]} result
          failed? (contains? #{:fail :error} (:status result))]
      (<< [:li {:data-test-id (str id) :data-status (name status)}
           [:div {:class [$test-row $row-hover]}
            [:input {:type "checkbox"
                     :checked (not excluded?)
                     :aria-label (str "Include " id)
                     :on-change {:e ::toggle-test! :id id}}]
            (status-icon status)
            [:span {:class $test-name :title (str id)} (str (:name info))]
            (when (and failed? (:file info))
              (<< [:span {:class $meta} (str (:file info) ":" (:line info))]))
            [:span {:class $spacer}]
            (when (contains? #{:pass :fail :error :outdated} status)
              (<< [:span {:class $meta} (assertion-text result)]
                  [:span {:class $meta} (format-duration duration)]))
            (run-button {:e ::run! :ids [id]} (str "Run " id))]

           (when (or (seq reports) (seq out))
             (<< [:div {:class $test-body}
                  (sg/simple-seq reports ui-report)
                  (when (seq out)
                    (<< [:details {:class $details :open failed?}
                         [:summary "Output"]
                         [:pre {:data-section "out"} out]]))]))]))))

;; namespaces

(defn ns-info [state ns visible-ids]
  (let [ids (get-in state [:namespaces ns :tests])
        excluded (get-in state [:prefs :excluded])
        statuses (map #(runner/status-of state %) ids)
        freqs (frequencies statuses)
        n-excluded (count (filter #(contains? excluded %) visible-ids))
        ns-reports (get-in state [:ns-reports ns])]
    {:status
     (cond
       (= ns (get-in state [:run :current-ns])) :running
       (or (pos? (:running freqs 0)) (pos? (:queued freqs 0))) :queued
       (or (pos? (:error freqs 0)) (seq ns-reports)) :error
       (pos? (:fail freqs 0)) :fail
       (pos? (:outdated freqs 0)) :outdated
       (= (count ids) (:not-run freqs 0)) :not-run
       :else :pass)
     :freqs freqs
     :total (count ids)
     :duration (reduce + 0 (keep #(get-in state [:results % :duration]) ids))
     :checked (cond
                (zero? n-excluded) :all
                (= n-excluded (count visible-ids)) :none
                :else :some)
     :expanded? (expanded? state ns)
     :ns-reports ns-reports}))

(defn counts-text [{:keys [freqs total]}]
  (if (= total (:pass freqs))
    (str total " passed")
    (->> [[:fail "failed"] [:error "error"] [:pass "passed"] [:outdated "outdated"] [:queued "queued"] [:running "running"] [:not-run "not run"]]
         (keep (fn [[k label]]
                 (let [n (get freqs k 0)]
                   (when (pos? n)
                     (if (= :error k)
                       (plural n label)
                       (str n " " label))))))
         (str/join ", "))))

(def $ns-item
  (css {:border-bottom "1px solid var(--st-border)"}))

(def $ns-row
  (css :flex :items-center :gap-2 :py-1 :px-3 {:min-height "34px"}))

(def $chevron
  (css :inline-flex :items-center :justify-center :cursor-pointer :shrink-0
    {:width "20px"
     :height "20px"
     :border "none"
     :background "transparent"
     :color "var(--st-muted)"
     :font-size "10px"
     :padding "0"}))

(def $ns-name
  (css :truncate :font-mono :font-semibold :cursor-pointer :text-left
    {:border "none"
     :background "transparent"
     :padding "0"
     :font-size "13px"}))

(def $ns-body
  (css :pb-2 {:padding-left "52px" :padding-right "12px"}))

(defc ui-ns [ns visible-ids]
  (bind {:keys [status checked expanded? duration ns-reports] :as info}
    (sg/watch runner/state-ref #(ns-info % ns visible-ids)))

  (render
    (<< [:li {:class $ns-item :data-ns (str ns) :data-status (name status)}
         [:div {:class [$ns-row $row-hover]}
          [:button {:class $chevron
                    :type "button"
                    :aria-expanded (str expanded?)
                    :aria-label (str (if expanded? "Collapse " "Expand ") ns)
                    :on-click {:e ::toggle-expand! :ns ns}}
           (if expanded? "▼" "▶")]
          [:input {:type "checkbox"
                   :checked (not= :none checked)
                   :indeterminate (= :some checked)
                   :aria-label (str "Include " ns)
                   :on-change {:e ::toggle-ns! :ns ns :ids visible-ids}}]
          (status-icon status)
          [:button {:class $ns-name :type "button" :on-click {:e ::toggle-expand! :ns ns}} (str ns)]
          [:span {:class $spacer}]
          [:span {:class $meta} (counts-text info)]
          [:span {:class $meta} (when (pos? duration) (format-duration duration))]
          (run-button {:e ::run! :ids visible-ids} (str "Run " ns))]

         (when (seq ns-reports)
           (<< [:div {:class $ns-body}
                (sg/simple-seq ns-reports ui-report)]))

         (when expanded?
           (<< [:ul {:class (css :m-0 :p-0 {:list-style "none"})}
                (sg/keyed-seq visible-ids identity ui-test)]))])))

;; header

(defn ?header [state]
  (let [ids (runner/all-test-ids state)
        scope (filterv (runner/scope-pred state) ids)]
    {:status (runner/overall-status state)
     :summary (runner/summarize state ids)
     :scope (count scope)
     :failed-in-scope (count (filter #(runner/failed? state %) scope))
     :filtered? (or (not (str/blank? (get-in state [:prefs :filter]))) (some? (:only state)))
     :run (select-keys (:run state) [:status :total :completed :started :finished])
     :run-failures (->> (get-in state [:run :test-ids])
                        (filter #(runner/failed? state %))
                        (count))}))

(def $header
  (css :sticky :top-0 :z-10
    {:background "var(--st-bg-subtle)"
     :border-bottom "1px solid var(--st-border)"}))

(def $bar
  (css :flex :items-center :flex-wrap :gap-3 :px-3 :pt-3 :pb-2))

(def $title
  (css :m-0 :text-base :font-semibold))

(def $pill
  (css :inline-flex :items-center :gap-1 :px-2 :font-semibold :text-sm
    {:border-radius "9999px"
     :padding-top "1px"
     :padding-bottom "1px"
     :color "#fff"
     :background "var(--st-idle)"}
    ["&[data-status=passed]" {:background "var(--st-pass)"}]
    ["&[data-status=failed]" {:background "var(--st-fail)"}]
    ["&[data-status=running]" {:background "var(--st-running)"}]))

(def $stats
  (css :text-xs {:color "var(--st-muted)" :font-variant-numeric "tabular-nums"}))

(def $toolbar
  (css :flex :items-center :flex-wrap :gap-2 :px-3 :pb-3))

(def $search
  (css :flex-1 :px-2 :py-1
    {:min-width "220px"
     :max-width "480px"
     :border "1px solid var(--st-border)"
     :border-radius "6px"
     :background "var(--st-bg)"
     :outline "none"}
    [:focus {:border-color "var(--st-accent)"}]))

(def $btn
  (css :px-3 :py-1 :cursor-pointer :whitespace-nowrap :font-medium
    {:border "1px solid var(--st-border)"
     :border-radius "6px"
     :background "var(--st-bg)"}
    [:hover {:border-color "var(--st-accent)"}]
    [:disabled {:opacity "0.5" :cursor "default" :border-color "var(--st-border)"}]))

(def $btn-primary
  (css {:background "var(--st-accent)"
        :border-color "var(--st-accent)"
        :color "var(--st-accent-fg)"}))

(def $toggle
  (css :inline-flex :items-center :gap-1 :text-sm :cursor-pointer :select-none :px-1))

(def $only
  (css :flex :items-center :gap-2 :px-3 :pb-2 :text-xs {:color "var(--st-muted)"}))

(def $link-btn
  (css :cursor-pointer :underline
    {:border "none" :background "transparent" :padding "0" :color "var(--st-accent)"}))

(def $progress
  (css {:height "3px" :background "transparent"}))

(def $progress-bar
  (css {:height "100%" :transition "width 120ms linear"}))

(defn status-text [{:keys [status summary run]}]
  (let [{:keys [fail error pass not-run outdated]} summary]
    (case status
      :running (str "Running " (:completed run) "/" (:total run))
      :failed (->> [(when (pos? fail) (str fail " failed"))
                    (when (pos? error) (plural error "error"))]
                   (remove nil?)
                   (str/join ", ")
                   (str "✕ "))
      :passed (if (pos? (+ not-run outdated))
                (str "✓ " pass " passed")
                (str "✓ All " pass " passed"))
      "Not run yet")))

(defn stats-text [{:keys [summary run]}]
  (let [{:keys [tests pass fail error not-run outdated assertions]} summary]
    (->> [(plural tests "test")
          (str pass " passed")
          (when (pos? fail) (str fail " failed"))
          (when (pos? error) (plural error "error"))
          (when (pos? outdated) (str outdated " outdated"))
          (when (pos? not-run) (str not-run " not run"))
          (plural assertions "assertion")
          (when-some [finished (:finished run)]
            (str "last run " (format-duration (- finished (:started run)))))]
         (remove nil?)
         (str/join " · "))))

(defc ui-header []
  (bind prefs (sg/watch runner/state-ref [:prefs]))
  (bind only (sg/watch runner/state-ref [:only]))
  (bind {:keys [status scope failed-in-scope filtered? run run-failures] :as header}
    (sg/watch runner/state-ref ?header))

  (render
    (let [running? (= :running status)
          status-name (name status)]
      (<< [:header {:class $header}
           [:div {:class $bar}
            [:h1 {:class $title} "Tests"]
            [:output {:class $pill :role "status" :aria-live "polite" :data-status status-name}
             (status-text header)]
            [:span {:class $stats} (stats-text header)]]

           [:div {:class $toolbar}
            [:input {:id "st-search"
                     :class $search
                     :type "search"
                     :placeholder "Filter tests, comma separated (press / to focus)"
                     :aria-label "Filter tests"
                     :autocomplete "off"
                     :spellcheck "false"
                     :value (:filter prefs)
                     :on-input ::filter!
                     :on-keydown ::filter-key!}]
            (if running?
              (<< [:button {:class $btn :type "button" :on-click ::cancel!} "Cancel"])
              (<< [:button {:class [$btn $btn-primary]
                            :type "button"
                            :disabled (zero? scope)
                            :title "Run all checked tests matching the filter (Enter)"
                            :on-click ::run-scope!}
                   (if filtered?
                     (str "Run " (plural scope "test"))
                     (str "Run all (" scope ")"))]))
            [:button {:class $btn
                      :type "button"
                      :disabled (or running? (zero? failed-in-scope))
                      :on-click ::run-failed!}
             (str "Run failed (" failed-in-scope ")")]
            [:label {:class $toggle :title "Re-run tests affected by code changes after hot-reload"}
             [:input {:type "checkbox" :checked (:autorun prefs) :on-change ::toggle-autorun!}]
             "Auto-run on reload"]
            [:label {:class $toggle}
             [:input {:type "checkbox" :checked (:only-failures prefs) :on-change ::toggle-only-failures!}]
             "Only failures"]]

           (when (seq only)
             (<< [:div {:class $only}
                  (str "Limited to: " (str/join ", " (sort only)))
                  [:button {:class $link-btn :type "button" :on-click ::clear-only!} "show all"]]))

           [:div {:class $progress}
            (when (:total run)
              (<< [:div {:class $progress-bar
                         :style {:width (str (if (pos? (:total run))
                                               (* 100 (/ (:completed run) (:total run)))
                                               100)
                                             "%")
                                 :background (cond
                                               (pos? run-failures) "var(--st-fail)"
                                               (= :running (:status run)) "var(--st-running)"
                                               :else "var(--st-pass)")}}]))]]))))

;; root

(defn ?visible
  "[[ns [visible-test-id ...]] ...] for the namespaces the list should show"
  [state]
  (let [pred (runner/filter-pred state)
        only-failures? (get-in state [:prefs :only-failures])]
    (->> (:ns-order state)
         (keep (fn [ns]
                 (let [ids (->> (get-in state [:namespaces ns :tests])
                                (filter pred)
                                (filter #(or (not only-failures?) (runner/failed? state %)))
                                (vec))]
                   (when (or (seq ids)
                             (and only-failures? (seq (get-in state [:ns-reports ns]))))
                     [ns ids]))))
         (vec))))

(defn run-ids! [ids]
  (when (seq ids)
    (runner/run-tests! ids {:reason :manual})))

(def $empty
  (css :p-6 :text-center {:color "var(--st-muted)"}))

(defc ui-root []
  (bind visible (sg/watch runner/state-ref ?visible))
  (bind test-count (sg/watch runner/state-ref #(count (:tests %))))

  (event ::filter! [env ev e]
    (swap! runner/state-ref assoc-in [:prefs :filter] (.. e -target -value)))

  (event ::filter-key! [env ev ^js e]
    (case (.-key e)
      "Enter"
      (when-not (runner/running?)
        (run-ids! (runner/tests-in-scope @runner/state-ref)))

      "Escape"
      (swap! runner/state-ref assoc-in [:prefs :filter] "")

      nil))

  (event ::run-scope! [env ev e]
    (run-ids! (runner/tests-in-scope @runner/state-ref)))

  (event ::run-failed! [env ev e]
    (let [state @runner/state-ref]
      (run-ids! (filterv #(runner/failed? state %) (runner/tests-in-scope state)))))

  (event ::run! [env {:keys [ids]} e]
    (run-ids! ids))

  (event ::cancel! [env ev e]
    (runner/cancel!))

  (event ::toggle-autorun! [env ev e]
    (swap! runner/state-ref update-in [:prefs :autorun] not))

  (event ::toggle-only-failures! [env ev e]
    (swap! runner/state-ref update-in [:prefs :only-failures] not))

  (event ::toggle-test! [env {:keys [id]} e]
    (swap! runner/state-ref update-in [:prefs :excluded]
      (fn [excluded]
        (if (contains? excluded id)
          (disj excluded id)
          (conj excluded id)))))

  (event ::toggle-ns! [env {:keys [ids]} e]
    (swap! runner/state-ref update-in [:prefs :excluded]
      (fn [excluded]
        (if (some #(contains? excluded %) ids)
          (reduce disj excluded ids)
          (into excluded ids)))))

  (event ::toggle-expand! [env {:keys [ns]} e]
    (swap! runner/state-ref
      (fn [state]
        (assoc-in state [:ui :expanded ns] (not (expanded? state ns))))))

  (event ::clear-only! [env ev e]
    (swap! runner/state-ref assoc :only nil))

  (render
    (<< [:div {:class "st-root"}
         (ui-header)
         [:main
          (cond
            (zero? test-count)
            (<< [:p {:class $empty} "No tests found. Tests are namespaces matching :ns-regexp (default \"-test$\") containing deftest."])

            (empty? visible)
            (<< [:p {:class $empty} "No tests match the current filter."])

            :else
            (<< [:ul {:class (css :m-0 :p-0 {:list-style "none"})}
                 (sg/keyed-seq visible first
                   (fn [[ns ids]]
                     (ui-ns ns ids)))]))]])))
