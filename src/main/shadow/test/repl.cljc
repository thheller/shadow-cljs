(ns shadow.test.repl
  (:require
    [clojure.test :as ct :refer (deftest is)]))

(defonce last-test-ref (atom nil))

;; ~selection - the current selection
;; ~selected-form - the currently selected form if the selection is a single valid form
;; ~file-name - the name of the current file
;; ~file-path - the full path of the current file
;; ~file-namespace - the namespace of the current file, if any, as a symbol
;; ~form-before-caret - the text of the form immediately before the caret
;; ~top-level-form - the text of the top-level form under the caret
;; ~current-var - the FQN of the current var under the caret
;; ~current-test-var - the FQN of the current var under the caret, if it represents a test
;l ~current-function - the FQN of the current var under the caret, if it represents a function

(defn run-test [{:keys [test-var] :as info}]
  (reset! last-test-ref test-var)

  (test-var))

(defn re-run []
  (let [test-var @last-test-ref]
    (if-not test-var
      (println "No test-var selected.")
      (ct/test-var test-var)
      )))

(deftest dummy-test
  (is (= 1 2)))
