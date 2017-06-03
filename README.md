# shadow-cljs

`shadow-cljs` aims to provide everything you need to compile your ClojureScript code with a focus on simplicity and ease of use. 

### Status: Alpha

This is still in an experimental stage, some things may still change. Documentation is lacking and may sometimes be a bit out of date. I'll update things once everything is sorted out.

## Features

- Good configuration defaults so you don't have to sweat the details
- Can be used as a library (within other tools like `lein` or `boot`)
- It can run standalone (via the `shadow-cljs` CLI script)
- Live code reloading
- CLJS REPL


## Installation

Library

[![Clojars Project](https://img.shields.io/clojars/v/thheller/shadow-cljs.svg)](https://clojars.org/thheller/shadow-cljs)

Standalone via [yarn](https://yarnpkg.com/en/package/shadow-cljs) or [npm](https://www.npmjs.com/package/shadow-cljs)
```
# yarn
yarn add shadow-cljs

# npm
npm install shadow-cljs
```

You may also install globally which makes things a bit easer to work with.

`yarn global add shadow-cljs` or `npm install --global shadow-cljs`.


## Configuration

*WIP*

`shadow-cljs` is configured by a `shadow-cljs.edn` file in your project root directory.

It should contain a map with some global configuration and a `:builds` key for all your builds.

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
   :modules {:main [my.app]}}}}
```

- `:dependencies` manage your CLJS dependencies in the same format as `leiningen` or `boot` 
- `:source-paths` define where the compiler will look for `.cljs` and `.cljc` files
- `:builds` can either be a vector or maps or nested maps where the key is used as the `:id` of your build

## Build Configuration

Each build in `shadow-cljs`must define a `:target` which defines where you intent your code to be executed. There are default built-ins for the Browser and `node.js`. They all share the basic concept of having `:dev` and `:release` modes. `:dev` mode provides all the usual development goodies like fast compilation, live code reloading and a REPL. `:release` mode will produce optimized output intended for production.

- TBD: `:npm-module` docs
- [Compiling for the browser](ClojureScript-for-the-browser) for the `:browser` target.
- [Compiling node.js scripts](ClojureScript-for-node.js-scripts) for the `:node-script` target.
- [Compiling node.js libraries](ClojureScript-for-node.js-libraries) for the `:node-library` target.

## Build Compilation

*WIP: details pending*


```
# command line overview
shadow-cljs -h

# compile a build once in :dev mode
shadow-cljs --build build-id --once

# compile and watch + REPL
shadow-cljs --build build-id --dev

# release
shadow-cljs --build build-id --release
```



## License

Copyright © 2017 Thomas Heller

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
