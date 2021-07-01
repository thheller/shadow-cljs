`shadow-cljs` provides everything you need to compile your ClojureScript code with a focus on simplicity and ease of use.

[![](https://img.shields.io/badge/Clojurians-shadow--cljs-lightgrey.svg)](https://clojurians.slack.com/messages/C6N245JGG/)
[![npm](https://img.shields.io/npm/v/shadow-cljs.svg)](https://github.com/thheller/shadow-cljs)
[![Clojars Project](https://img.shields.io/clojars/v/thheller/shadow-cljs.svg)](https://clojars.org/thheller/shadow-cljs)

<a href="http://shadow-cljs.org" target="_blank"><img src="https://raw.githubusercontent.com/thheller/shadow-cljs/master/src/main/shadow/cljs/devtools/server/web/resources/img/shadow-cljs.png" width="120" height="120" /></a>

## Features

- Good configuration defaults so you don't have to sweat the details
- Seamless `npm` integration
- Fast builds, reliable caching, ...
- Supporting various targets `:browser`, `:node-script`, `:npm-module`, `:react-native`, `:chrome-extension`, ...
- Live Reload (CLJS + CSS)
- CLJS REPL
- Code splitting (via `:modules`)

![overview-img](https://user-images.githubusercontent.com/116838/28730426-d32dc74a-7395-11e7-9cec-54275af35345.png)

## Requirements

- [node.js](https://nodejs.org) (v6.0.0+, most recent version preferred)
- [npm](https://www.npmjs.com) (comes bundled with `node.js`) or [yarn](https://www.yarnpkg.com)
- [Java SDK](https://adoptopenjdk.net/) (Version 8+, Hotspot)

## Quick Start

Creating your project can be done quickly using the `npx create-cljs-project` utility. `npx` is part of `npm` and lets us run utility scripts quickly without worrying about installing them first. The installer will create a basic project scaffold and install the latest version of `shadow-cljs` in the project.

```bash
$ npx create-cljs-project acme-app
npx: installed 1 in 5.887s
shadow-cljs - creating project: .../acme-app
Creating: .../acme-app/package.json
Creating: .../acme-app/shadow-cljs.edn
Creating: .../acme-app/.gitignore
Creating: .../acme-app/src/main
Creating: .../acme-app/src/test
----
Installing shadow-cljs in project.

npm notice created a lockfile as package-lock.json. You should commit this file.
+ shadow-cljs@<version>
added 88 packages from 103 contributors and audited 636 packages in 6.287s
found 0 vulnerabilities

----
Done.
----
```

The resulting project has the following structure

```bash
.
├── node_modules (omitted ...)
├── package.json
├── package-lock.json
├── shadow-cljs.edn
└── src
    ├── main
    └── test
```

`shadow-cljs.edn` will be used to configure your CLJS builds and CLJS dependencies. `package.json` is used by `npm` to manage JS dependencies.

Everything is ready to go if you just want to start playing with a REPL

```bash
$ npx shadow-cljs node-repl
# or
$ npx shadow-cljs browser-repl
```

When building actual projects we need to configure the build first and create at least one source file.

The default source paths are configured to use `src/main` as the primary source directory. It is recommended to follow the [Java Naming Conventions](https://docs.oracle.com/javase/tutorial/java/package/namingpkgs.html) to organize your CLJS namespaces. It is recommended to start all namespaces with a unique enough prefix (eg. company name, project name) to avoid conflicts with generic names such as `app.core`. Suppose you were building a Web Frontend for [Acme Inc.](https://en.wikipedia.org/wiki/Acme_Corporation) using `acme.frontend.app` might be a good starting point as it can easily grow to include `acme.backend.*` later on.

Using the above example the expected filename for `acme.frontend.app` is `src/main/acme/frontend/app.cljs`.

Lets start with a simple example for a browser-based build.

```clojure
(ns acme.frontend.app)

(defn init []
  (println "Hello World"))
```

Inside the `shadow-cljs.edn` `:builds` section add

```clojure
{...
 :builds
 {:frontend
  {:target :browser
   :modules {:main {:init-fn acme.frontend.app/init}}
   }}}
```

This config tells the compiler to call `(acme.frontend.app/init)` when the generated JS code is loaded. Since no `:output-dir` is configured the default `public/js` is used. You can start the development process by running:

```bash
$ npx shadow-cljs watch frontend
...
a few moments later ...
...
[:frontend] Build completed. (134 files, 35 compiled, 0 warnings, 5.80s)
```

The compilation will create the `public/js/main.js` we configured above (`:main` becomes `main.js` in the `:output-dir`). Since we want to load this in the browser we need to create a HTML file in `public/index.html`.

```html
<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <title>acme frontend</title>
  </head>
  <body>
    <div id="root"></div>
    <script src="/js/main.js"></script>
  </body>
</html>
```

We also need a simple HTTP server to serve our HTML since modern Browsers all place a few restrictions on files loaded directly from disk which will lead to issues later. `shadow-cljs` provides such a server but you can use anything you like at this point. It only matters that the files from the `public` directory are served properly. To start the built-in web server just adjust the build config from above.

```clojure
{...
 :dev-http {8080 "public"}
 :builds
 {:frontend
  {:target :browser
   :modules {:main {:init-fn acme.frontend.app/init}}
   }}}
```

Once the config is saved the server will automatically start and serve the content at http://localhost:8080. There is no need to restart `shadow-cljs`. When opening the above URL the Browser Console should show "Hello World". 


To be continued ...

## Documentation

Please refer to the [User Manual](https://shadow-cljs.github.io/docs/UsersGuide.html). (Work in Progress)

## Video Courses

- [EN] [Learn Reagent Free](https://www.jacekschae.com/learn-reagent-free/uk29i?coupon=SHADOW) - [reagent](https://github.com/reagent-project/reagent)+[firebase](https://firebase.google.com/) demo application built using shadow-cljs
- [EN] [Learn Reagent Pro](https://www.jacekschae.com/learn-reagent-pro/uk29i?coupon=SHADOW) [Affiliate Link, 30$ discount] - [reagent](https://github.com/reagent-project/reagent)+[firebase](https://firebase.google.com/) demo application built using shadow-cljs
- [EN] [Learn re-frame](https://www.learnreframe.com/?ref=uk29i) [Affiliate Link] - re-frame SPA tutorial
- [EN] [ClojureScript for React Developer - Building Conduit](https://www.youtube.com/watch?v=EUdsLZUsiRQ&list=PLUGfdBfjve9VGzp7G1C9FYfH8Yk1Px-11)

## Guides

- [EN] [A beginner guide to compile ClojureScript with shadow-cljs](https://medium.com/@jiyinyiyong/a-beginner-guide-to-compile-clojurescript-with-shadow-cljs-26369190b786)
- [CN] [shadow-cljs 2.x 使用教程](https://segmentfault.com/a/1190000011499210)
- [EN] [ClojureScript with Middleman via Shadow-CLJS](http://bobnadler.com/articles/2018/01/28/clojurescript-with-middleman-via-shadow-cljs.html)
- [EN] [Clojurescript Development for JavaScript Developers in Atom with Shadow-cljs and ProtoREPL](https://medium.com/@loganpowell/clojurescript-development-for-javascript-developers-in-atom-with-shadow-cljs-and-protorepl-ec5e38e3de26)
- [EN] [Confidence and Joy: React Native Development with ClojureScript and re-frame](https://hackmd.io/@byc70E6fQy67hPMN0WM9_A/rJilnJxE8)
- ... please let me know if you created something to include here

## Examples

- [Official Browser Example](https://github.com/shadow-cljs/quickstart-browser)
- [re-frame-template](https://github.com/day8/re-frame-template) -  Leiningen template that creates a [re-frame](https://github.com/Day8/re-frame) project using the [shadow-cljs](https://github.com/thheller/shadow-cljs/) build tool with many optional extras.
- [mhuebert/shadow-re-frame](https://github.com/mhuebert/shadow-re-frame) - Usage of [re-frame](https://github.com/Day8/re-frame), [re-frame-trace](https://github.com/Day8/re-frame-trace), and the [shadow-cljs](https://github.com/thheller/shadow-cljs/) build tool. **[Live Demo](https://mhuebert.github.io/shadow-re-frame/)**
- [jacekschae/shadow-reagent](https://github.com/jacekschae/shadow-reagent) - shadow-cljs + proto-repl + reagent
- [jacekschae/shadow-firebase](https://github.com/jacekschae/shadow-firebase) - shadow-cljs + firebase
- [ahonn/shadow-electron-starter](https://github.com/ahonn/shadow-electorn-starter) - ClojureScript + Shadow-cljs + Electron + Reagent
- [jacekschae/conduit](https://github.com/jacekschae/conduit) - Real world application built with shadow-cljs + re-frame + re-frame-10x <br> [Demo](https://jacekschae.github.io/conduit-re-frame-demo/) | [Demo with re-frame-10x](https://jacekschae.github.io/conduit-re-frame-10x-demo/)
- [quangv/shadow-re-frame-simple-example](https://github.com/quangv/shadow-re-frame-simple-example) - a simple re-frame + shadow-cljs example.
- [CryptoTwittos](https://github.com/teawaterwire/cryptotwittos) - reagent, re-frame, web3
- [loganpowell/shadow-proto-starter](https://github.com/loganpowell/shadow-proto-starter) - shadow-cljs, Atom, Proto-REPL, node.js library
- [manuel-uberti/boodle](https://github.com/manuel-uberti/boodle) - re-frame based Accounting SPA with `deps.edn` based backend
- [shadow-cljs-kitchen-async-puppeteer](https://github.com/iku000888/shadow-cljs-kitchen-async-puppeteer) - Automated browser test with puppeteer and cljs.test, built with shadow-cljs
- [baskeboler/cljs-karaoke-client](https://github.com/baskeboler/cljs-karaoke-client) - web karaoke player using shadow-cljs + reagent + re-frame + re-frame-10x + re-frame-async-flow-fx + build hooks for minifying css and generating seo pages ([Demo](https://karaoke-player.netlify.app/songs/Rolling%20Stones-all%20over%20now%20rolling%20stones.html))
- [flexsurfer/ClojureRNProject](https://github.com/flexsurfer/ClojureRNProject) - simple React Native application with ClojureScript, re-frame and react navigation v5
- [jacekschae/shadow-cljs-devcards](https://github.com/jacekschae/shadow-cljs-devcards) - how to configure devcards with shadow-cljs
- [jacekschae/shadow-cljs-tailwindcss](https://github.com/jacekschae/shadow-cljs-tailwindcss) - shadow-cljs + tailwindcss-jit setup
- [ertugrulcetin/racing-game-cljs](https://github.com/ertugrulcetin/racing-game-cljs) - A 3D racing game built with ClojureScript, React and ThreeJS
- ... please let me know if you created something to include here

## Libraries
- [flexsurfer/rn-shadow-steroid](https://github.com/flexsurfer/rn-shadow-steroid) - React Native with shadow-cljs on steroids
- [re-frame-flow](https://github.com/ertugrulcetin/re-frame-flow) - A graph based visualization tool for re-frame event chains using shadow-cljs
- ... please let me know if you created something to include here

## License

Copyright © 2020 Thomas Heller

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
