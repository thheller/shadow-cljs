`shadow-cljs` provides everything you need to compile your ClojureScript code with a focus on simplicity and ease of use.

[![Clojars Project](https://img.shields.io/clojars/v/thheller/shadow-cljs.svg)](https://clojars.org/thheller/shadow-cljs)
[![Clojurians - shadow-cljs](https://img.shields.io/badge/Clojurians-shadow--cljs-lightgrey.svg)](https://clojurians.slack.com/messages/C6N245JGG/)

## Features

- Good configuration defaults so you don't have to sweat the details
- Live code reloading
- CLJS REPL
- JS dependencies via `npm`
- Code splitting (via `:modules`)
- Fast builds, reliable caching, ...


## Guides

- [EN] [A beginner guide to compile ClojureScript with shadow-cljs](https://medium.com/@jiyinyiyong/a-beginner-guide-to-compile-clojurescript-with-shadow-cljs-26369190b786)
- [CN] [shadow-cljs 2.x 使用教程](https://segmentfault.com/a/1190000011499210)
- More coming soon ...


## Installation

`shadow-cljs` is easiest to use by installing the `npm` package.

```bash
# npm
npm install --save-dev shadow-cljs
# yarn
yarn add --dev shadow-cljs
yarn shadow-cljs --help
```

To make things easier to use you might also want to install it globally so you have access to the `shadow-cljs` command without `./node_modules/.bin/shadow-cljs`. The global install is optional but recommended.

```bash
# npm
npm install -g shadow-cljs
# yarn
yarn global add shadow-cljs
```

`shadow-cljs` will always use the version installed in your project, so you can keep track of the version you want to use in your `package.json`.

## Quick Start

`shadow-cljs` is configured by a `shadow-cljs.edn` file in your project root directory. You can create a default one by running `shadow-cljs init`.

It should contain a map with some global configuration and a `:builds` entry for all your builds.


```edn
{:source-paths ["src"]
 :dependencies []
 :builds {}}
```

- `:source-paths` define where the compiler will look for `.cljs` and `.cljc` files
- `:dependencies` manage your CLJS dependencies in the same format as `leiningen` or `boot`
- `:builds` is a map or build-id (a keyword) to the build config.

An example config could look like this:

```edn
{:source-paths ["src"]
 :dependencies [[reagent "0.8.0-alpha1"]]
 :builds {:app {:target :browser
                :output-dir "public/js"
                :asset-path "/js"
                :modules {:main {:entries [my.app]}}}}}
```

The file structure for this example should look like this:

```
.
├── package.json
├── shadow-cljs.edn
└── src
    └── my
        └── app.cljs
```

## Compilation

`shadow-cljs` has 2 compilation modes:

* `:dev`, it will inject a few development helpers for dealing with things like a CLJS REPL and live code reloading
* `:release`, in this mode those things will not be included and the code will be optimized by the Closure Compiler.

### Development

```bash
# compile a build once in :dev mode
shadow-cljs compile app

# compile and watch
shadow-cljs watch app

# connect to REPL for the build (available while watch is running)
shadow-cljs cljs-repl app
```

### Production/Release

This will run the compiler with optimized settings for production builds.

```bash
shadow-cljs release app
```

Sometimes you may run into some release issues due to `:advanced` compilation. These commands can help track down the causes.

```bash
shadow-cljs check app
shadow-cljs release app --debug
```

## Build Targets

Each build in `shadow-cljs` must define a `:target` which defines where you intent your code to be executed. There are default built-ins for the Browser and `node.js`. They all share the basic concept of having `:dev` and `:release` modes. `:dev` mode provides all the usual development goodies like fast compilation, live code reloading and a REPL. `:release` mode will produce optimized output intended for production.

- [Compiling for the browser](https://github.com/thheller/shadow-cljs/wiki/ClojureScript-for-the-browser) for the `:browser` target.
- [Compiling node.js scripts](https://github.com/thheller/shadow-cljs/wiki/ClojureScript-for-node.js-scripts) for the `:node-script` target.
- [Compiling node.js libraries](https://github.com/thheller/shadow-cljs/wiki/ClojureScript-for-node.js-libraries) for the `:node-library` target.
- TBD: `:npm-module` docs

## License

Copyright © 2017 Thomas Heller

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
