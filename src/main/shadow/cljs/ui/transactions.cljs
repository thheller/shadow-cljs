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

(deftx process-worker-output
  "any update from a build worker"
  {:build-id keyword?
   :type keyword?})

(deftx process-supervisor
  "a build worker was started/stopped"
  {:op #{:worker-start :worker-stop}
   :build-id keyword?})
