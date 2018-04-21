# Contributing

## Process
Most of the standard open-source and Github workflow applies. 

Pull requests are welcome, and maintainers reserve the right to decide what 
ends up in the codebase. If you want to increase the chances your contribution 
will make it into the codebase, open an issue describing the features or changes you'd like included before making a PR. 
Once a PR is opened, maintainers may still request changes.

## Setting up

Make a fork of the repo and clone your fork onto your local machine.

Have a look around! Since `shadow-cljs` is a build tool, there are multiple build configurations in the project's own `shadow-cljs.edn` 
that you can use to debug and develop features.

## Starting a REPL
Development is almost entirely REPL-based, so when you're ready to dive in, you'll want to start a REPL.

Starting one and connecting the editor of your choice should be the same as any Leiningen-based project. 

When all else fails, this should work with all nREPL compatible editors:

1. `lein repl`
2. Establish remote nREPL connection to port in `.nrepl-port`


