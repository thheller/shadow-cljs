`shadow-cljs` provides everything you need to compile your ClojureScript code with a focus on simplicity and ease of use.

[![](https://img.shields.io/badge/Clojurians-shadow--cljs-lightgrey.svg)](https://clojurians.slack.com/messages/C6N245JGG/)
[![npm](https://img.shields.io/npm/v/shadow-cljs.svg)](https://github.com/thheller/shadow-cljs)
[![Clojars Project](https://img.shields.io/clojars/v/thheller/shadow-cljs.svg)](https://clojars.org/thheller/shadow-cljs)

<a href="http://shadow-cljs.org" target="_blank"><img src="https://raw.githubusercontent.com/thheller/shadow-cljs/master/src/main/shadow/cljs/devtools/server/web/resources/img/shadow-cljs.png" width="120" height="120" /></a>

## Features

- Good configuration defaults so you don't have to sweat the details
- Supporting various targets `:browser`, `:node-script`, `:npm-module`, `:react-native`(exprimental)...
- Live Reload (CLJS + CSS)
- CLJS REPL
- Importing CommonJS & ES6 modules from npm or local JavaScript files
- Code splitting (via `:modules`)
- Fast builds, reliable caching, ...

![overview-img](https://user-images.githubusercontent.com/116838/28730426-d32dc74a-7395-11e7-9cec-54275af35345.png)


## Documentation

Please refer to the [User Manual](https://shadow-cljs.github.io/docs/UsersGuide.html). (Work in Progress)

## Guides

- [EN] [A beginner guide to compile ClojureScript with shadow-cljs](https://medium.com/@jiyinyiyong/a-beginner-guide-to-compile-clojurescript-with-shadow-cljs-26369190b786)
- [CN] [shadow-cljs 2.x 使用教程](https://segmentfault.com/a/1190000011499210)
- [EN] [ClojureScript with Middleman via Shadow-CLJS](http://bobnadler.com/articles/2018/01/28/clojurescript-with-middleman-via-shadow-cljs.html)
- ... please let me know if you created something to include here

## Examples

- [Official Browser Example](https://github.com/shadow-cljs/quickstart-browser)
- [mhuebert/shadow-re-frame](https://github.com/mhuebert/shadow-re-frame) - Usage of [re-frame](https://github.com/Day8/re-frame), [re-frame-trace](https://github.com/Day8/re-frame-trace), and the [shadow-cljs](https://github.com/thheller/shadow-cljs/) build tool. **[Live Demo](https://mhuebert.github.io/shadow-re-frame/)**
- [jacekschae/shadow-reagent](https://github.com/jacekschae/shadow-reagent) - shadow-cljs + proto-repl + reagent
- [jacekschae/shadow-firebase](https://github.com/jacekschae/shadow-firebase) - shadow-cljs + firebase
- [ahonn/shadow-electron-starter](https://github.com/ahonn/shadow-electorn-starter) - ClojureScript + Shadow-cljs + Electron + Reagent
- [jacekschae/conduit](https://github.com/jacekschae/conduit) - Real world application built with shadow-cljs + re-frame + re-frame-10x <br> [Demo](https://jacekschae.github.io/conduit-re-frame-demo/) | [Demo with re-frame-10x](https://jacekschae.github.io/conduit-re-frame-10x-demo/)
- [quangv/shadow-re-frame-simple-example](https://github.com/quangv/shadow-re-frame-simple-example) - a simple re-frame + shadow-cljs example.
- ... please let me know if you created something to include here

## License

Copyright © 2018 Thomas Heller

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
