# shadow-devtools (ALPHA)

This library provides a basic configuration layer for ClojureScript and attempts to provide some good defaults so you can limit the amount of config repetition for your builds. 

Each build defines a `:target` and has two different modes (`:dev` or `:release`).

The `:target` describes where you want your build to run. `shadow-devtools` by default defines `:browser`, `:node-library` and `:node-script` but you can easily add custom targets (or modify the defaults).

A `:dev` mode build will compile with `{:optimizations :none}`, source maps and all other development time goodies (REPL, live-reload, ...). It attempts to inject all `:dev` related things for you, so there should be no need to configure any development stuff inside your code.

`:release` mode will apply `:optimizations` suitable for the `:target` (ie. `:advanced` for the browser, `:simple` for `node.js`). It will also apply all other options meant for production builds so you don't have to worry about them. `:dev` related things are not included of course.

## Configuration

Build configs are handled by a `shadow-cljs.edn` file in your project root (next to `project.clj`). It is expected to contain a vector of maps. Each map must at least have an `:id` and a `:target`. Every other entry in the build config depends on the chosen `:target`.

The default `:target` implementations can be found [here](https://github.com/thheller/shadow-devtools/tree/master/src/main/shadow/cljs/devtools/targets). Each `:target` can define a `clojure.spec` for the config options it supports. Some specs are already implemented.

`leiningen` (or `boot`) are used to handle all JVM related things like `:dependencies` or `:source-paths`. Note that you can put all source files into one directory if you want. The reasons why some other build tools enforce multiple `:source-paths` for multiple builds do not apply here. You can still use them if you like though.

### :browser

A browser build always defines one or more `:modules`. Each `:module` must define one or more `:entries` which is a symbol for CLJS namespace. This allows for efficent code splitting into multiple output files if desired. You can just define one `:module` if that is all you want. These are `Closure Modules` but somewhat different from the way they are implement in `cljs.closure`. They also work in `:dev`.

`:public-dir` defines the directory all output will be written to. This example will produce a `public/js/main.js` and `public/js/extra.js`. During development a `public/js/cljs-runtime` directory will also be produced that will contain all support files (the actual code, source maps, etc.). In general a `:public-dir` should be dedicated to one build and you cannot compile multiple builds into the same directory as they will conflict with each other.

`:public-path` defines the path the browser will use to reference these files. This should be an absolute path when using a web-server, but a relative path works too.

```clojure
[{:id :my-build
  :target :browser
  :public-dir "public/js"
  :public-path "/js"
  :modules
  {:main
   {:entries [my.app]}
   :extra
   {:entries [my.app.extra]
    :depends-on #{:main}}} 
  }]
```

In your HTML you would include `<script src="/js/main.js"></script>` (+ `extra.js` if needed). This applies to both `:dev` and `:release`. No need for `goog/base.js` or `goog.require`.

### :node-script

This creates a standalone `.js` file intended to be called via `node script.js <command line args>`. It will call the `(demo.script/main <command line args>)` function on startup. This only ever produces the file specified in `:output-to` all other support files will be written to a temporary support directory in `:dev` mode. In `:release` mode the file is completely standalone and does not require anything else.

The [demo.script](https://github.com/thheller/shadow-devtools/blob/master/src/dev/demo/script.cljs) includes an example setup to make the live-reloading work. Given the nature of most `node.js` libraries some live-reloading might not work too well. The REPL should just work though.

```clojure
[{:id :script
  :target :node-script
  :main demo.script/main
  :output-to "out/demo-script/script.js"
  }]
```

### :node-library

This create a `.js` file intended to be consumed via the normal `node.js` `require` mechanism. As `:node-script` this will only create the file specified in `:output-to`. The `:exports` map maps CLJS vars to the name they should be exported to.

```clojure
[{:id :library
  :target :node-library
  :output-to "out/demo-library/lib.js"
  :exports
  {:hello demo.lib/hello}}]
```

And then consume it via
```
cd out/demo-library
node
> var x = require('./lib');
undefined
> x.hello()
hello
'hello'
```

While in development both `:node-script` and `:node-library` require 2 dependencies which you can install via `npm`. A `:release` mode build does not need these dependencies.

In the directory of `:output-to` create a `package.json` (or use `npm` to create it for you).

```json
{
  "devDependencies": {
    "source-map-support": "^0.4.14",
    "ws": "^2.2.3"
  }
}
```


## Usage

[![Clojars Project](https://img.shields.io/clojars/v/thheller/shadow-devtools.svg)](https://clojars.org/thheller/shadow-devtools)

There are several ways to produce code for your builds. They all start by adding

```clojure
[thheller/shadow-devtools "0.1.2017040316"]
```

to your `project.clj` `:dependencies`. I recommend to use the `:dev` profile.

### From the command line:

Start a `:dev` mode build with a REPL and live-reload:
```
lein run -m shadow.cljs.devtools.cli/dev <build-id>
```

Just compile `:dev` mode once, no REPL or live-reload:
```
lein run -m shadow.cljs.devtools.cli/once <build-id>
```

Create a `:release` mode optimized build:
```
lein run -m shadow.cljs.devtools.cli/release <build-id>
```

### REPL

Start a `clojure.main` REPL. This **DOES NOT WORK** while in nREPL unless your nREPL client supports `needs-input`.
```
lein run -m clojure.main
;; I recommend using rlwrap, Cursive can also start this for you.
rlwrap lein run -m clojure.main
```

```clojure
(require '[shadow.cljs.devtools.api :as cljs])

(cljs/once {:build :your-build-id})
;; or
(cljs/release {:build :your-build-id})
```

The `shadow.cljs.devtools.api/dev` fn will turn your REPL into a CLJS REPL and auto-compile your project in the background:
```
(cljs/dev {:build :your-build-id})
```

You can stop the build worker by exiting the REPL, just type `:repl/quit` or `:cljs/quit` which will drop you back down the the CLJ REPL you started with.

### Embedded (dev only)

Embedded mode is about background worker management. A Worker is responsible for compiling your build on file changes and handling the REPL. You may start one worker per build, they can run in parallel.

```clojure
(require '[shadow.cljs.devtools.embedded :as cljs])
(cljs/start-worker :your-build-id)
```

You can open a REPL for that worker at any point:
```clojure
(cljs/repl :your-build-id)
```
You can exit that REPL with `:repl/quit`.

Stop one particular build:
```clojure
(cljs/stop-worker :your-build-id)
```

Stop all builds:
```clojure
(cljs/stop!)
```


## License

Copyright © 2017 Thomas Heller

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
