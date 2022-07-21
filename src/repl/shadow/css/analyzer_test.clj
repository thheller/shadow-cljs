(ns shadow.css.analyzer-test
  (:require
    [clojure.pprint :refer (pprint)]
    [shadow.css.index :as index]
    [shadow.css.analyzer :as ana]
    [shadow.css.build :as build]
    [shadow.css.specs :as s]
    [clojure.test :as ct :refer (deftest is)]
    [clojure.string :as str]))

(deftest analyze-form
  (tap>
    (ana/process-form
      (build/start {})
      {:ns 'foo.bar
       :line 1
       :column 2
       :form '(css :px-4 :my-2
                "pass"
                :c-text-1
                [:>md :px-6]
                [:>lg :px-8
                 [:&hover :py-10]])})))


(deftest index-src-main
  (time
    (tap>
      (-> (index/create)
          (index/add-path "src/main" {})
          (index/write-to "src/dev/shadow-css-index.edn")))))

(deftest build-src-main
  (time
    (tap>
      (-> (build/start {})
          (build/generate '{:output-dir "tmp/css"
                            :chunks {:main {:include [shadow.cljs.ui.*]}}})))))

(defn parse-tailwind [[tbody tbody-attrs & rows]]
  (reduce
    (fn [all row]
      (let [[_ td-key td-val] row
            [_ _ key] td-key
            [_ _ val] td-val

            rules
            (->> (str/split val #"\n")
                 (reduce
                   (fn [rules prop+val]
                     (let [[prop val] (str/split prop+val #": " 2)]
                       (assoc rules (keyword prop) (subs val 0 (dec (count val))))))
                   {}
                   ))]

        (assoc all (keyword key) rules)))
    {}
    rows))

(deftest test-parse-tailwind
  (let [s
        [:tbody {:class "align-baseline"} [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400"} "ring-0"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300"} "box-shadow: var(--tw-ring-inset) 0 0 0 calc(0px + var(--tw-ring-offset-width)) var(--tw-ring-color);"]] [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400 border-t border-slate-100 dark:border-slate-400/10"} "ring-1"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300 border-t border-slate-100 dark:border-slate-400/10"} "box-shadow: var(--tw-ring-inset) 0 0 0 calc(1px + var(--tw-ring-offset-width)) var(--tw-ring-color);"]] [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400 border-t border-slate-100 dark:border-slate-400/10"} "ring-2"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300 border-t border-slate-100 dark:border-slate-400/10"} "box-shadow: var(--tw-ring-inset) 0 0 0 calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color);"]] [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400 border-t border-slate-100 dark:border-slate-400/10"} "ring"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300 border-t border-slate-100 dark:border-slate-400/10"} "box-shadow: var(--tw-ring-inset) 0 0 0 calc(3px + var(--tw-ring-offset-width)) var(--tw-ring-color);"]] [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400 border-t border-slate-100 dark:border-slate-400/10"} "ring-4"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300 border-t border-slate-100 dark:border-slate-400/10"} "box-shadow: var(--tw-ring-inset) 0 0 0 calc(4px + var(--tw-ring-offset-width)) var(--tw-ring-color);"]] [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400 border-t border-slate-100 dark:border-slate-400/10"} "ring-8"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300 border-t border-slate-100 dark:border-slate-400/10"} "box-shadow: var(--tw-ring-inset) 0 0 0 calc(8px + var(--tw-ring-offset-width)) var(--tw-ring-color);"]] [:tr [:td {:translate "no" :class "py-2 pr-2 font-mono font-medium text-xs leading-6 text-sky-500 whitespace-nowrap dark:text-sky-400 border-t border-slate-100 dark:border-slate-400/10"} "ring-inset"] [:td {:translate "no" :class "py-2 pl-2 font-mono text-xs leading-6 text-indigo-600 whitespace-pre dark:text-indigo-300 border-t border-slate-100 dark:border-slate-400/10"} "--tw-ring-inset: inset;"]]]



        rules (parse-tailwind s)]
    (doseq [rule (sort (keys rules))]
      (println (str rule " " (pr-str (get rules rule))))
      )))