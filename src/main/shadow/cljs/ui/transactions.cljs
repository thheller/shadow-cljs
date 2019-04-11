(ns shadow.cljs.ui.transactions
  (:require [shadow.cljs.ui.fulcro-mods :as fm :refer (deftx)]))

(deftx select-build)

(deftx builds-loaded
  "the original build list finished loading"
  {})

(deftx set-page
  "sets current router page"
  {:page ident?})

(deftx ws-open
  "the api websocket connected"
  {})

(deftx ws-close
  "the api websocket disconnected"
  {})

(deftx build-watch-start
  "(shadow/watch build-id)"
  {:build-id keyword?})

(deftx build-watch-stop
  "(shadow/stop-worker build-id)"
  {:build-id :keyword?})

(deftx build-watch-compile
  "force a re-compile of a build worker (might be stuck)
   (shadow/watch-compile! build-id)"
  {:build-id keyword?})

(deftx build-compile
  "(shadow/compile build-id)"
  {:build-id keyword?})

(deftx build-release
  "(shadow/release build-id)"
  {:build-id keyword?})

(deftx process-worker-broadcast
  "any update from a build worker"
  {:build-id keyword?
   :type keyword?})

(deftx process-build-status-update
  "updated build status"
  {:build-id keyword?
   :build-status any?})

(deftx process-supervisor
  "a build worker was started/stopped"
  {:op #{:worker-start :worker-stop}
   :build-id keyword?})

(deftx process-tool-msg
  {:op #{:runtime-connect :runtime-disconnect}})

(deftx process-repl-input
  {:text string?})

(deftx repl-session-start
  {:runtime-id some?})

(deftx inspect-build-ns
  {:ns symbol?})

(deftx toggle-notifications
  {:wanted boolean?})