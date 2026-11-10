(ns shadow.test.browser.ui
  (:require
    [clojure.java.io :as io]
    [shadow.css.build :as cb]))

(defmacro generated-css
  "CSS for all (css ...) uses in the shadow.test.browser.ui namespace.

   generated when the namespace is compiled and embedded as a string, so the test runner
   works in any project without extra build steps, in dev and release builds alike."
  []
  (let [src
        (slurp (io/resource "shadow/test/browser/ui.cljs"))

        state
        (-> (cb/init)
            (cb/load-default-aliases-from-classpath)
            (cb/load-colors-from-classpath)
            (cb/generate-color-aliases)
            (cb/generate-spacing-aliases)
            (cb/index-source src)
            (cb/generate '{:ui {:entries [shadow.test.browser.ui]}})
            (cb/minify))

        {:keys [css warnings]}
        (get-in state [:chunks :ui])]

    (doseq [warning warnings]
      (binding [*out* *err*]
        (prn [::css-warning warning])))

    css))
