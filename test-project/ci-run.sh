#!/bin/sh

set -e

npm install

npx shadow-cljs --cli-info

npx shadow-cljs clj-run test.runnable/foo

npx shadow-cljs release reagent test-node test-karma --verbose

node out/test-node/script.js

apt-get update
apt-get install chromium --no-install-recommends -y

CHROME_BIN=/usr/bin/chromium npx karma start --single-run