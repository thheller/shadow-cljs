#!/bin/sh

set -e

npm install

npx shadow-cljs --cli-info

npx shadow-cljs clj-run test.runnable/foo

npx shadow-cljs release reagent test-node --verbose

node out/test-node/script.js
