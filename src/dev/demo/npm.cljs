(ns demo.npm
  (:require ["react" :as react]))

(js/console.log (react/createElement "div" nil "foo"))

(defn ^:export foo []
  "hello from cljs!")

