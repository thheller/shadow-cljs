#!/bin/sh

set -e

rm -rf packages/launcher/web/js

lein with-profiles +cljs run -m shadow.cljs.devtools.cli release launcher-main launcher-renderer
