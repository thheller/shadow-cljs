(ns shadow.npm
  (:refer-clojure :exclude (require))
  (:require [clojure.java.io :as io]))

;; these are macros because require is supposed to be static

(defmacro require [name]
  `(js/require ~name))

(defmacro require-file
  "requires a file relative to the current working directory"
  [name]
  `(js/require ~(str "../../" name)))