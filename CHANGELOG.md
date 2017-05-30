# Changelog

## [1.0.20170530](https://github.com/thheller/shadow-cljs/compare/1.0.20170527...1.0.20170530)

### Features
- [WIP] REPL for webpack builds

### Changes
- refactor some code generation, always generates flat files now
- `$CLJS` is now the name for the global in `:advanced` mode, barely makes a difference with gzip compared to just `$`. should avoid any potential naming conflicts.

### Fixes
- fixed :npm-module require order
- the closure constants pass could sometimes move constants to invalid places (before cljs.core in the dependency order). also removed the deprecated API call.
- can now call js requires as functions `(:require ["npm-thing" :as thing]) (thing)"` [CLJS-1968](https://dev.clojure.org/jira/browse/CLJS-1968)
- restructure file-reloading in the browser so it works with webpack builds


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
