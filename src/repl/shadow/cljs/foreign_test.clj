(ns shadow.cljs.foreign-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.edn :as edn]
            [shadow.build.closure :as closure]
            [cljs.closure]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
    ;; too much util ...
            [shadow.cljs.devtools.server.util :as server-util]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [shadow.build.ns-form :as ns-form]
            [shadow.build.api :as cljs]
            [shadow.build :as comp]
            [shadow.cljs.devtools.api :as api]
            [shadow.build.npm :as npm]
            [clojure.inspector :as i])
  (:import (com.google.javascript.jscomp SourceFile DiagnosticGroups CheckLevel CompilerOptions CodePrinter$Builder CompilerOptions$LanguageMode)
           (com.google.javascript.jscomp.deps ModuleLoader$ResolutionMode)
           (java.io File StringWriter)))

