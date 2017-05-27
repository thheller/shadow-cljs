# Changelog

## master (unreleased)

...

## [1.0.20170527](https://github.com/thheller/shadow-cljs/tree/1.0.20170527)

### Features

- [experimental] add support for `:advanced` in `:npm-module` target builds
- add support for `"output-dir"` in `package.json`, defaults to `node_modules/shadow-cljs`
- :output-dir and :asset-path (to match CLJS, same as :public-dir and :public-path)

### Changes

- Text format for Closure Compiler warnings/errors
- refactored most of the internals shadow.cljs.closure namespace, no API changes

### Fixes

- write all cache files to `target/shadow-cljs/...` instead of 3 different places
- Fix warning about missing `goog.nodeGlobalRequire` when using `--check`
