# shadow.repl

Enabling Tools to enhance your REPL experience without sacrifice.

## The Problem

There have been a few discussions about REPLs on the mailing list but nothing really went anywhere. `tools.nrepl` is the de-facto "standard" for Tools while some users stick to simpler solutions like `inferior-lisp` from emacs or even just terminals.

Well, I have no idea what others are doing really so I will try to do a Problem Statement based on how I see it. You can probably skip this and look at my proposed solution. I'm really interested in some feedback. I can't be the only one in this situation.

This is all based on my personal experience and I haven't read much about other solutions outside the Clojure world. I watched some [cool things](https://www.youtube.com/watch?v=SrKj4hYic5A) a while ago which inspired some thought but never went anywhere until now.

### User Perspective

My expectation from a REPL is: You type text in, some text is printed as the result.

That means a REPL implementation should `read` from `*in*` then `eval` and `print` text to `*out*`. Not sure about `*err*` as Sockets don't have this concept.

This also means that I expect to be able to nest one REPL inside another. When I have a CLJ REPL but want to start a CLJS REPL, it should be as simple as calling `(start-my-repl)`. If I quit that REPL I drop back down the CLJ REPL like nothing happened.

### Editor/Tool Perspective

The above is just a stream of text, parsing that is probably a recipe for disaster. Yes the `clojure.main/repl` implementation does allow you to hook into every aspect of the loop but that only works when the REPL is started by the Tool itself. Should the user start another REPL the Tool is completely unaware of it since from its perspective the `read` didn't return yet.

The most popular/common alternative is `tools.nrepl` which is message based and breaks my expectation of a REPL. It does however provide extension mechanisms so a Tool can provide additional features (ie. autocomplete).

Running a CLJS REPL inside nREPL is very confusing and error-prone. Support got better over the past few years but it is still far from perfect.

The [figwheel](https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl) wiki has this to say:

> Even though this has recently become easier, you should still consider setting up a workflow that includes nREPL as an advanced undertaking as it requires a lot of contextual knowledge of the Clojure environment if something goes wrong.

It should not be this hard.


### REPL-Implementor Perspective

I wrote a CLJS REPL. I gave up on implementing a nREPL "middleware" because I could not stop thinking "this should not be this hard" (again). Since no Tool would be aware of my implementation I would not get any Tool support whatsoever anyways.

My editor of choice is Cursive, which defaults to nREPL though. Luckily it also supports `clojure.main`. The issue however is if I want to use Cursive features like "Send X to REPL" it will wrap my code in some other code. That works well enough until you start a CLJS REPL. Since that is not longer eval'd in Clojure it breaks completely. Cursive does have a toggle to switch to CLJS mode which will then not wrap the code but I always forget set the correct mode.

I don't want to pick on Cursive here, I had the exact same problems in emacs. I have not used CIDER in a while so I'm sure it has improved a lot. That highlights another issue though, all the work that went into CIDER probably didn't do much for Cursive (unless I'm mistaken and Cursive actually uses `cider-nrepl`, correct me if I'm wrong please).

# The Solution?

The Goal is to expose more information about the running REPL that a Tool can use while keeping the `*in*` and `*out*` model. Network access would be nice as well but should be optional. I want to be able to start a REPL in a terminal and it should just work. When I start it in my Editor (or connect remotely) it should just work but provide some more extra features.

I went through some iterations but really the only missing part is making the REPL inspectable from outside the loop itself. While also providing a way for the REPL implementation to expose additional features it may support. 

To implement this I introduced two new concepts: `roots` each with one a more `levels`.

A `root` basically refers to a pair of `*in*` and `*out*`. The default for the JVM being stdin/stdout but with network access each new Socket connection would also be a new root.

Every supported REPL will then register itself as a `level` when it starts, it can exports additional features at this point.

# Example

You can try this by starting a normal `clojure.main` REPL.

First we need to start this so a proper root is started.
```
(require 'shadow.repl-demo)
(shadow.repl-demo/main)

;; which should show you
REPL ready, type :repl/quit to exit
[1:0] user=> 
```

Now to make things clearer lets start another level

```
(shadow.demo-repl/repl)
```

Your prompt should now show:

```
[1:1] user=> 
```

The first number is the id of the `root`. The second the id of the `level`. If you type `:repl/quit` it will exit the current level, all the way back down to the `clojure.main`.

You can inspect your current level by calling `(shadow.repl/level)`.

```
#:shadow.repl{:lang :clj, :get-current-ns #object[shadow.repl_demo$repl$fn__173 0x3b77a04f "shadow.repl_demo$repl$fn__173@3b77a04f"], :root-id 1, :level-id 1}
```

That looks a bit like gibberish so lets do

```
(require '[clojure.pprint :refer (pprint)])
(set! *print-namespace-maps* false)
(pprint (shadow.repl/level))
```

```
{:shadow.repl/lang :clj,
 :shadow.repl/get-current-ns
 #object[shadow.repl_demo$repl$fn__173 0x3b77a04f "shadow.repl_demo$repl$fn__173@3b77a04f"],
 :shadow.repl/root-id 1,
 :shadow.repl/level-id 1}
```

These are the things the `(shadow.repl/repl)` REPL exposes to the outside world. It is a map of namespaced keywords to anything. `:shadow.repl/get-current-ns` in this case is a function that takes no arguments and returns its current `ns`.

```
((-> (shadow.repl/self) ::repl/get-current-ns)) ;; => "user"
(in-ns 'shadow.repl-demo)
((-> (shadow.repl/self) ::repl/get-current-ns)) ;; => "shadow.repl-demo"
```

Inspecting your own loop probably isn't all that interesting but the whole point of this is that others can as well.

The demo has a simple TCP server that will dump the current state of all roots in a simplified form. 

```
(def x (shadow.repl-demo/simple-server))
```

You can get its output via `nc localhost 5000` or `telnet localhost 5000`.

```
[1 0 :clj "user"]
[1 1 :clj "shadow.repl-demo"]
```

Now for fun lets start a remote server `(def y (shadow.repl-demo/start-server))` and `telnet localhost 5001` into it.

These functions can be used to inspect the current state of any `root`/`level`.
```
(shadow.repl/roots)
(shadow.repl/root)
(shadow.repl/level)
```

The `:shadow.repl/get-current-ns` feature is just an example of how this would work. The REPL in question can export anything it wants here. There should probably be a standard somewhere for which keyword has which meaning. Maybe even use `clojure.spec` to make it formal.
