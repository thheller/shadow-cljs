# Changelog

## master (unreleased)

### Features

- add support for ``"output-dir"` in `package.json`, defaults to `node_modules/shadow-cljs`
- :output-dir and :asset-path (to match CLJS, same as :public-dir and :public-path)

### Changes

- Text format for Closure Compiler warnings/errors

### Fixes

- write all cache files to `target/shadow-cljs/...` instead of 3 different places
- Fix warning about missing `goog.nodeGlobalRequire` when using `--check`
