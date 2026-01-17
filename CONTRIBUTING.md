# Contributing

## Process

Most of the standard open-source and Github workflow applies. 

Pull requests are welcome, and maintainers reserve the right to decide what ends up in the codebase. If you want to increase the chances your contribution will make it into the codebase, open an issue describing the features or changes you'd like included before making a PR. Once a PR is opened, maintainers may still request changes.

`shadow-cljs` is built with extensibility in mind so most enhancements and features can probably be built without touching the main project. Ideally start with simple functions in a library. Not only does this make it easier to assess what is actually done, it might also open it up to inclusion in other projects.

## Setting up

Make a fork of the repo and clone your fork onto your local machine.

Have a look around! Since `shadow-cljs` is a build tool, there are multiple build configurations in the project's own `shadow-cljs.edn` that you can use to debug and develop features.

## Starting a REPL

Development is almost entirely REPL-based, so when you're ready to dive in, you'll want to start a REPL.

Starting one and connecting the editor of your choice should be the same as any deps.edn project. 

When all else fails, this should work with all nREPL compatible editors:

1. `clj -M:dev:start`
2. Establish remote nREPL connection to port in `.nrepl-port`
3. Run `(require 'repl) (repl/go)` in the REPL to get a basic development server running. You can run `(repl/go)` at any point to restart this server.


