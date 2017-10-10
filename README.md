
# ![overview-img](https://user-images.githubusercontent.com/116838/28730426-d32dc74a-7395-11e7-9cec-54275af35345.png)

`shadow-cljs` provides everything you need to compile your ClojureScript code with a focus on simplicity and ease of use.

### Quick Guide

Say the project is called `app`:

```
$ tree src/
src/
└── app
    ├── lib.cljs
    └── main.cljs
```

To compile `app`, install the npm package:

```bash
npm install -g shadow-cljs
```

Add a `shadow-cljs.edn` file with configurations:

```edn
{:source-paths ["src/"]
 :dependencies []
 :builds {:app {:output-dir "target/"
                :asset-path "."
                :target :browser
                :modules {:main {:entries [app.main]}}
                :devtools {:after-load app.main/reload!}}}}
```

To compile code:

```bash
shadow-cljs compile app
shadow-cljs watch app # watch files, recompile and trigger `app.main/reload!`
shadow-cljs release app # with optimizations
```

### Status: Alpha

This is still in an alpha stage. Documentation is lacking and may sometimes be a bit out of date. I'll update things once everything is sorted out. The core parts provided by `shadow-build` were in production use for 3+ years and have now been merged into this project. `shadow-cljs` just adds a better configuration layer so you don't need to work with the low-level API.

[![](https://img.shields.io/badge/Clojurians-shadow--cljs-lightgrey.svg)](https://clojurians.slack.com/messages/C6N245JGG/)

## Features

- Good configuration defaults so you don't have to sweat the details
- It can run standalone (via the `shadow-cljs` CLI script)
- Can be used as a library (directly from Clojure or via other tools like `lein` or `boot`)
- Live code reloading
- CLJS REPL

## Rationale

This project started because I wanted more control over the CLJS build process than the default build API allows. It still uses the default CLJS analzer/compiler but by replacing the build API I have full control over which code gets generated and how it is bundled together.

It all started when I wanted support for code splitting via Google Closure Modules (ie. `:modules`) for my work projects. CLJS didn't support it at the time and still only has partial support at best. I also didn't like being forced to organize code into multiple source paths as my projects grew. Since then I have implemented many more things and diverged quite a bit from the default CLJS build API and other tools that are built on top of it like `lein-figwheel`, `lein-cljsbuild` or `boot-cljs`.

`shadow-cljs` provides a few configuration presets that generate fine-tuned code for a given `:target` environment:

- `:browser` is optimized for JS used in HTML environments
- `:node-script` is optimized for `node.js` scripts/apps that run standalone
- `:node-library` is optimized for `node.js` libraries that are used by other `node.js` code
- `:npm-module` generates code that is compatible with existing JS tools (eg. `webpack`, `create-react-app`, `create-react-native-app`) to make it easier to integrate CLJS into an existing JS codebase without replacing the entire JS toolchain.
- Custom targets can easily be added and can control each step in the build process

Each build `:target` can run in `:dev` mode which focuses on the developer experience with fast incremental builds and automatically injects all the necessary code required for live-reloading and the REPL. All this is configured via the build config and not in code.

To complement that each build has a `:release` mode that focuses on production quality code which is optimized by the Closure Compiler. `:dev` things will be kept out of it automatically and you don't need to manually juggle source paths to keep certain things out.

Both modes use the same build config where each mode can fine-tune just the parts that actually need to change, no need to copy the entire config.

## Installation

### Library

[![Clojars Project](https://img.shields.io/clojars/v/thheller/shadow-cljs.svg)](https://clojars.org/thheller/shadow-cljs)

API Docs coming soon ...

### Standalone via [yarn](https://yarnpkg.com/en/package/shadow-cljs) or [npm](https://www.npmjs.com/package/shadow-cljs)
```
# yarn
yarn global add shadow-cljs

# npm
npm install -g shadow-cljs
```

Installing `shadow-cljs` globally makes it easier to use from the command line.

I recommend adding it to your project as well via `yarn add --dev shadow-cljs` (or `npm install --save-dev shadow-cljs`) so you have the dependency in your project and can keep track of it there. This is optional though. If you have it installed in your project the `shadow-cljs` command will use it over the global version.

You may also skip the global install entirely and just run everything via `./node_modules/.bin/shadow-cljs` or via the `"scripts"` entry in your `package.json`.

## Configuration

*WIP*

`shadow-cljs` is configured by a `shadow-cljs.edn` file in your project root directory.

It should contain a map with some global configuration and a `:builds` entry for all your builds.

```clojure
{:dependencies
 []

 :source-paths
 ["src"]

 :builds
 {:app
  {:target :browser
   :output-dir "public/js"
   :asset-path "/js"
   :modules {:main {:entries [my.app]}}}}}
```

- `:dependencies` manage your CLJS dependencies in the same format as `leiningen` or `boot`
- `:source-paths` define where the compiler will look for `.cljs` and `.cljc` files
- `:builds` can either be a vector or maps or nested maps where the key is used as the `:id` of your build

## Build Configuration

Each build in `shadow-cljs` must define a `:target` which defines where you intent your code to be executed. There are default built-ins for the Browser and `node.js`. They all share the basic concept of having `:dev` and `:release` modes. `:dev` mode provides all the usual development goodies like fast compilation, live code reloading and a REPL. `:release` mode will produce optimized output intended for production.

- [Compiling for the browser](https://github.com/thheller/shadow-cljs/wiki/ClojureScript-for-the-browser) for the `:browser` target.
- [Compiling node.js scripts](https://github.com/thheller/shadow-cljs/wiki/ClojureScript-for-node.js-scripts) for the `:node-script` target.
- [Compiling node.js libraries](https://github.com/thheller/shadow-cljs/wiki/ClojureScript-for-node.js-libraries) for the `:node-library` target.
- TBD: `:npm-module` docs

## Build Compilation

*WIP: details pending*


```
# command line overview
shadow-cljs -h

# compile a build once in :dev mode
shadow-cljs compile build-id

# compile and watch
shadow-cljs watch build-id

# REPL for the build
shadow-cljs cljs-repl build-id

# release
shadow-cljs release build-id
```

## License

Copyright © 2017 Thomas Heller

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
