# ESM Braindump

The idea of writing an alternate CLJS compiler that emits "modern" ESM JavaScript code instead of ClojureJS has been circling in my head for years now. Still not sure this would be worth doing but I need to get this out of my head so I can stop thinking about it.

I assume that the reader is familiar with ESM and how JavaScript works otherwise this won't be very easy to follow.

## Motivation

ClosureJS **the module format** is dead. **Not the Compiler or Library**. Can't find the reference but the Google Closure devs said they are moving the Closure Library over to more modern formats (possibly even Typescript). This process started years ago by moving parts from `goog.provide/require` to `goog.modules`. Over time mode actual ESM code should make it in. The Closure Compiler nowadays supports ESM code just fine so there really is no reason for ClosureJS to exist anymore (more on that later). Heck even today the `goog.module` code requires transpilation and isn't directly loadable as their `goog.provide/require` counterparts were.

ClosureJS is also the biggest blocker for smoother interop with the broader JS ecosystem. The JS world never adopted it and getting the code to work with other JS requires all sort of ugly hacks (eg. glue code using globals).

When Rich Hickey first announced ClojureScript the choice of using ClosureJS was the correct one. Nowadays not so much, there is an actual standard now. Note that I'm **ONLY** referring to the ClosureJS "module" format used by the Closure Library and Closure Compiler. The Closure Compiler is just as relevant as ever and still the best option, it just also supports ESM just fine today. Obviously it didn't back then, but it does now. It is an actual standard that is not going anywhere.

## The "Problem" to Fix

The ClosureJS output mode using a ad-hoc namespacing system setup by `goog.provide`. `goog.provide("cljs.core");` will create a nested `var cljs = { core: {} };` object in the **global scope** and any additional "var" will just be added to that object. (eg. `cljs.core.assoc = function() ...`). For the Compiler `goog.require` statements are added to wire up dependencies required to be loaded before the actual file. At runtime these don't have any effect besides "guarding" against trying to load files in the wrong order. The "Debug Loader" actually ensures that files are loaded in the correct order. The Debug Loader is only used in unoptimized builds as the Closure Compiler remove/rewrite this ad-hoc namespacing system completely when optimizing.

The Debug Loader however makes integrating with other systems hard. The Closure Library has one Debug Loader implementation (which regular CLJS still uses) but `shadow-cljs` has had its own loading mechanism for years now. This fixes some issues with loading but overall the main issue persists. For each target platform this loader needs to use whatever loading mechanism the platform provides to emulate its own loading. For the browser this was traditionally done by emitting `<script>` tags in the correct order or loading the files via XHR. This is actually pretty terrible performance-wise for bigger project which is why I added [:loader-mode :eval](https://clojureverse.org/t/improving-initial-load-time-for-browser-builds-during-development/2518) to `shadow-cljs`. Initially for `:browser` builds only but nowadays `:react-native` uses the same strategy.

The Debug Loader really gets in the way when trying to interface with other JS code. Other runtimes/tools do not understand it. Each platform had its own mechanism to load code but nowadays ESM is well-supported so there is one **Standard** way to do this today.

Fixing this "Problem" would allow easy interop between CLJS and regular JS. The code would be directly loadable in the Browser without any Debug Loader mechanism or further processing. The code would be consumable by any runtime/tool supporting ESM. Giving us much broader access to the JS ecosystem.

## ClosureJS: The Good Part

ClosureJS however actually has one really useful aspect to it that we are exploiting heavily in CLJS. Everything lives in the same shared **global scope**. This is extremely useful and makes things like the REPL and hot-reload trivial to implement. Common JS HMR (hot-module-replacement) methods are extremely complex when compared to this. ESM did not make this simpler, quite the opposite.

For us since everything is namespaced and global we can just go and replace this at runtime without problems. At any point in the browser console or REPL via CLJS we can just redeclare anything and most code will use the new code immediately. Hot-reload can just eval the new file over the old one and redeclare all references in the process.

The namespacing system is far from perfect, but the shared global scope is bliss from a tool/debugging perspective. You can just use the browser devtools to look at live references (eg. `my.app.some_atom`). Good luck doing that with ESM.

One little annoyance is the nested nature of namespaces. I think it would have been better to not nest them but ClosureJS did at the time and it is too late to change now. The problem is that certain namespaces and vars can clash but in practice this doesn't matter much.

```clojure
(ns my.app)

(defn foo [] ...)

;; not possible, clashes with foo from above
(ns my.app.foo)
```

Instead of using `goog.provide("my.app.foo")` this could have done `goog.provide("cljs__my__app__foo")` or some other naming scheme to avoid those conflicts. Where each "namespace" is its own actual object but other namespaces are not nested in it. Instead you'd have another `cljs__my__app.foo` with no risk of conflicts.

## ESM: The Bad Part

ESM and pretty much all other alternate "module" systems (eg. CommonJS) however operate by providing each "namespace", "module" or "file" really with it's own scope. This is done so that `var x = 1;` in one module does not clash with `var x = 2;` in another one which was a big problem back in the "everything-is-global" days. In ClosureJS/CLJS everything is namespaced for this reason but writing the namespacing code by hand is kinda tedious so JS world never wanted to do that.

Since each module has its own scope you can't easily access other modules. Code needs to be explicitly exported and imported and there are limits to how much you can modify that after the fact. In ESM you cannot add exports after a file has been loaded, so going into a certain namespace and declaring a new `def` is possible but it can't be accessed by anything outside that file. There is limited insight into modules from the outside which is why you can't just look at the state of most JS apps.

The above assumes that the output by the CLJS compiler would map directly to strict ESM code.

```clojure
;; my/app.cljs
(ns my.app)

(defn hello []
  (js/console.log "hello world"))
```

maps to

```js
// my/app.js
export function hello() {
  console.log("hello world");
}
```
Consumable via
```html
<script type="module">
  import { hello } from "./my/app.js";
  hello();
</script>
```

While this would be neat from an interop perspective it really doesn't fit into how CLJS wants to operate. Having access to everything everywhere is kind of a base requirement and without that things get rather complicated quickly.

While it may be "bad" to just use fully qualified names in your code it is entirely valid.

```clojure
(ns my.app)

(defn hello []
  (js/console.log (clojure.string/upper-case "hello world")))
```

The `ns` does not have a proper `:require` for `clojure.string` so it is relying on that namespace being loaded before this code actually runs. This behavior is kind of undefined in the face of parallel compilation so it should not be relied upon. It is in fact relied upon in macros where the emitted code may contain references to code the macro has in fact loaded but was not explicitely loaded by the consuming `ns`.

```clojure
(ns my.app
  (:require [some.lib :as lib]))
  
(defn hello []
  (lib/fancy-macro "hello world"))

;; cljs
(ns some.lib
  (:require-macros [some.lib])
  (:require [clojure.string :as str]))

;; clj
(ns some.lib)

(defmacro fancy-macro [foo]
  `(clojure.string/upper-case ~foo))
```

`my.app` does not have a direct `:require` for `clojure.string` but the emitted code is completely valid since `some.lib` ensured that the namespace is loaded and compiled. So if we were using strict ESM import/export this would need to be accounted for. While this could be done statically during regular compilation it cannot be done so dynamically when compiling for the REPL since we cannot dynamically add `import` after the fact. There is dynamic `import()` but that is different.

So instead all of this most likely would need to emulate some kind of namespacing system on top of ESM to get our beloved sort of "global" world back. At which point I really wonder if this is worth doing at all in the first place.

On the one hand I do think yes, just to get that smoother interop. Don't mind the little boilerplate we need to add. On the other hand I think we can just add a little boilerplate and stick with what we have today.

In fact this is what `shadow-cljs` does in 2 different ways.


## Attempt #1 `:npm-module`

This admittedly poorly named `:target` actually just outputs the code in CommonJS wrappers. Each namespace will be available as its own separate file which is directly loadable via regular CommonJS `require`.

```
(ns my.app)

(defn hello []
  (assoc nil 1 2)) 
```
usually just
```js
goog.provide("my.app");
goog.require("cljs.core");

my.app.hello = function my$app$hello () {
   return cljs.core.assoc(nil, 1, 2);
};
```

gets some extra boilerplate so that "global" references like the `cljs` object are available. Without this the access to the `cljs.core.assoc` will fail because `cljs` is not defined in the current scope.

Greatly simplified basically all namespaces will just get a little preamble that pulls the "global" we want into the local scope. It would also append a bit of code to add public vars for the exported names

```
var $CLJS = require("./cljs_env.js");
require("./cljs.core.js");
var cljs = $CLJS.cljs;
var my = $CLJS.my ||= {};
// now this can work
cljs.core.assoc(...)

// and exporting stuff
module.exports.hello = my.app.hello;
```

Technically the `goog.provide` could have just created `global.cljs.core` but with `:npm-module` I explicitly tried to be a good citizen and NOT pollute the global scope. In hindsight this was not worth the effort at all.

Since all namespaces have this boilerplace code the resulting output is quite a bit larger than it otherwise should be. Code size doesn't matter too much when building for `node` but the builds where the output would be consumed by something like `webpack` this would become rather annoying since webpack would add its own boilerplate too. With `:advanced` the output would still be acceptable but still bigger than it should be.

## Attempt #2: :esm

The newer [:target :esm](https://clojureverse.org/t/generating-es-modules-browser-deno/6116) is an attempt to address some of the issues `:npm-module` had. Instead of CommonJS it emits ESM import/export statements (duh). Instead of providing every single namespace as an addressable artifact it just provides a configurable `:exports` option which maps CLJS fully qualified names to regular ESM `export` names. This means the final output contains significantly less boilerplate code and things like code-splitting `:modules` still work reasonably well.

I made no attempt at isolating development code and instead the unoptimized output just uses the global scope via `globalThis`. This means there will be a global `cljs` and others. This enables most features like hot-reload or REPL to work well enough. It will blow up in systems that don't support `globalThis` but everything I tested works fine (eg. browser, node, deno). Optimized code does not expose any globals nor does it rely on `globalThis` and should work in all ESM compliant systems. The REPL and hot-reload code still require host-specific code but that can be added in a straightforward way.

In theory the ESM target could replace most others but due to it being a bit too generic the dedicated targets will probably remain the better option for a while.

Since this intergrates directly with regular ESM `import` we gain new interesting features that weren't feasible before such as importing code directly without actually bundling it.

In the announcement post I used this example

```clojure
(ns my.app
   (:require ["https://cdn.pika.dev/preact@^10.0.0" :as preact]))
```

This will let the browser do the work of loading this package at runtime instead of having shadow-cljs bundle it. For certain use cases this may be useful and makes the overall output more modular. To differentiate between code that should be bundled and code that should be loaded at runtime I also added the `esm:` prefix.

```clojure
(ns my.app
  (:require ["esm:../foo.js" :as foo]))
```

Once again this will leave resolving and loading `../foo.js` to the runtime (eg. browser) and this output could be any other ESM file (eg. `tsc` output). The path is relative to the `:output-dir` files that were created as opposed to being relative to the source file. The other code can also directly load the exported CLJS code without ever having to be an actual part of the bundling process. It still won't be possible or desirable to combine two different CLJS builds this way. Interop however with other systems will be much easier.

Apart from the slightly dirty `globalThis` use during development this actually gives us most of the desirable features of ESM without having to do too much work in the Compiler to go full ESM.

## Attempt #3: Full ESM (not yet)

This is the part I've been thinking about but not written anything for. What could things look like if there was an actual pure ESM output with no ClosureJS or even Closure Library/Compiler but that does work with the REPL, hot-reload and still supports `:advanced` fully?

I honestly don't know. I have some rough ideas but in the end they don't gain anything over what we currently have. Whenever I see someone struggling with integrating CLJS with anything JS based I think this shouldn't be this hard but honestly CLJS is only part of the problem here.

I will not be working on this anytime soon I think. Yes, it would be neat to have more "modern" output and maybe even gain more modern features like `async/await` but in the end things would still more or less have the same issues it has now. `:target :esm` might be good enough after a few more tweaks.

## Non-Goals

A topic that comes up every now and then publishing CLJS libraries directly to `npm` (or other package registries). Mostly JS devs ask for this given that they are used to consuming `npm` packages and wonder why there isn't an easy way to consume `@cljs/core` or `@my-company/lib-a` like packages in "other" projects. Bigger projects are structured that way to be more modular. We also do this for Clojure and the JVM so this is a rather common practice that is not specific for CLJS or `npm`.

In theory it would be nice to sneak CLJS into a bigger project this way and just slowly replacing JS/TS code with CLJS. In practice however this does not work. You can write such a package with either `:npm-module` or `:esm` and publish it just fine. The consumer wouldn't even need to know the package was written in CLJS.

The issue however is that as soon as you do this for more than one package they each will have their own `cljs.core` bundled and as such their own datastructure implementation (eg. `{}` or `[]`)  would be different and not compatible with each other. It also makes the final artifacts rather large since each package may contain many duplicate things and not just `cljs.core`.

Theoretically once you have ESM code you could just take those separate files and publish them as packages without any optimizations. Since the output knows how to load ESM code it could just load `cljs.core` from somewhere (eg. `https://some.cdn/org.clojure/clojurescript/1.10.773/cljs/core.js`) instead of its own version. This may appear totally plausible from a JS perspective but it doesn't make sense for from CLJS at all. 

First and probably most important it doesn't contain any macros. While JS code doesn't care about this we really want to be able to use macros in CLJS code. The CLJS compiler can't really consume the code this way and requires a local version to get the macros and analyzer data. The analyzer data is just data so that could just be alongside the file and wouldn't be much of a problem. No, we don't want to publish macros this way. The compiled code could also be from a different compiler version and you just end up with a huge pile of complexity for little to no gain. The JS ecosystem is a perfect example where this leads. I won't go into details on this but if you follow the JS ecosystem you might know what I'm taking about. They are still trying to fix it with no end in sight. The CLJS world doesn't need this.

You also lose the desirable `:advanced` optimizations. It is nice to keep things modular but it is also nice to not have to worry about dead code so much. This may be less relevant for non-browser builds but it is rather useful thing to have. 

I understand why people keep asking for this but giving a satisfying answer is difficult as it requires a deeper understanding of all the parts involved. I hope I scratched the surface a little bit. You can still totally do this, just not how you might be used to. It is absolutely possible to incrementally introduce CLJS into an existing JS project today.

