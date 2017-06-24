# Changelog

## [1.0.20170624](https://github.com/thheller/shadow-cljs/compare/1.0.20170622-19...1.0.20170624)

- another AOT fix
- server now generates one file per port, eg. `target/shadow-cljs/nrepl.port`
- simple HTTP file server per build, configured via `:devtools {:http-root "path-to-dir" :http-port 8080}`. this is launched whenever `watch` is running for the given build.

## [1.0.20170622-19](https://github.com/thheller/shadow-cljs/compare/1.0.20170620...1.0.20170622-19)

- added basic nREPL support with piggieback emulation [WIP]
- rewrite CLI script to parse args instead of just passing them to the JVM. This allows the script to show `-h` without launching a JVM.
- add `--cli-info` option to print some basic info about the CLI command (paths, versions, dependencies, ...)
- hopefully resolve all AOT issues by deleting all AOT `.class` files when `:dependencies` change (or CLI version upgrade)
- watch mode would never attempt recompiles when the first compile failed
- basic `shadow-cljs test` command to run all `cljs.test` tests via `node` [WIP]

## [1.0.20170618](https://github.com/thheller/shadow-cljs/compare/1.0.20170618...1.0.20170620)

- fix some AOT issues that caused weird errors
- first pass making errors prettier as well
- [BREAKING] refactor all CLI commands, see `shadow-cljs -h`
- [BREAKING] http, socket-repl now use random ports by default, add `:http {:port 12345}` to get a fixed port.
- experimental nREPL support, still work in progress.

## [1.0.20170618](https://github.com/thheller/shadow-cljs/compare/1.0.20170615-09...1.0.20170618)

- fix broken node REPL `require`
- add support for `ns` in REPL
- add support for string requires in REPL `(require '["fs" :as fs])`
- Closure warnings/errors are now pretty as well

## [1.0.20170615-09](https://github.com/thheller/shadow-cljs/compare/1.0.20170615...1.0.20170615-09)

### Fixes

- fix broken websocket in non-server mode #50

## [1.0.20170615](https://github.com/thheller/shadow-cljs/compare/1.0.20170613...1.0.20170615)

### Features

- remote mode. If a `shadow-cljs --server` is running the `shadow-cljs` script will now connect to that instead of launching a new JVM. Leading to much faster response times.
- `shadow-cljs --repl` will just connect to the server and start a CLJ REPL

### Fixes

- long lines could break the warning printer #49

## [1.0.20170613](https://github.com/thheller/shadow-cljs/compare/1.0.20170610...1.0.20170613)

### Features
- `--server` mode for the CLI, starts a shared JVM process that can be used to run multiple builds in parallel.
- Clojure Socket REPL started by `--server` (default at localhost:8201). `rlwrap nc localhost 8201` is the simplest possible client.

### Changes

- Warning output with color

## [1.0.20170610](https://github.com/thheller/shadow-cljs/compare/1.0.20170603...1.0.20170610)

### Changes
- improved warning output, they now include a little source excerpt ala figwheel
- no longer `:verbose` by default, now configured via `:verbose true` in `shadow-cljs.edn`
- CLI script now scans parent directories when looking for config
- reworked CLI script to AOT compile some code to improve startup time (done once per version)

## [1.0.20170603](https://github.com/thheller/shadow-cljs/compare/1.0.20170602...1.0.20170603)

### Fixes
- fixed that the `:npm` config was no longer optional, it still should be

## [1.0.20170602](https://github.com/thheller/shadow-cljs/compare/1.0.20170601...1.0.20170602)

### Features

- [BREAKING] a shadow-cljs.edn config file is now mandatory, the script can create on for you. It is now also expected to contain a map and supports `:builds`, `:dependencies`, `:source-paths` and probaly more in the future. If you had a vector before just wrap it in `{:builds the-vector}`.
- [BREAKING] the package.json `"shadow-cljs"` entry is no longer used
- the CLI script will now AOT-compile the CLJ sources to optimize startup speed. This only needs to be done once for each shadow-cljs version but reduces startup time from ~12s to ~3s.

### Changes

- refactored most the the CLI script. It is now written in [CLJS](https://github.com/thheller/shadow-cljs/blob/master/src/main/shadow/cljs/npm/cli.cljs).
- refactored the dependency resolver/downloader to only do that and no longer launch the main process. It now only creates the classpath which is then used by another process. If the classpath is not modified this step is skipped.

## [1.0.20170601](https://github.com/thheller/shadow-cljs/compare/1.0.20170531...1.0.20170601)

### Features

- add support for "./foo" relative requires in the ns form. they are resolved relative to the source file, not the generated output file since that should be more obvious.

### Fixes

- properly output build warnings to the console, instead of just dumping the raw data.

## [1.0.20170531](https://github.com/thheller/shadow-cljs/compare/1.0.20170530...1.0.20170531)

### Fixes
- fix broken :node-script/:node-library :dev builds
- fix source maps containing ``"lineCount":null` so closure doesn't blow up

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
