# shadow.repl

Enabling tools to enhance your REPL experience without sacrifice.

By my definition a REPL implementation should `read` from `*in*` then `eval` and `print` text to `*out*` and maybe write to `*err*`.

# clojure.main/repl

The default `clojure.main/repl` provides exactly the above while letting you override each step.

https://clojure.github.io/clojure/clojure.main-api.html#clojure.main/repl

- `:init`
- `:need-prompt`
- `:prompt`
- `:flush`
- `:read`
- `:eval`
- `:print`
- `:caught`

It assumes the presence of 

- `*in*` which must satisfy `java.io.PushbackReader`
- `*out*`/`*err*` which must implement `java.io.Writer`


These are enough if you are working in a terminal and don't have any other ways of displaying additional information. It works well enough for simple synchronous interactions but quickly deteriorates as soon as async things happen.

```
(dotimes [x 10] (future (Thread/sleep 1) (prn :fooooo)))
nil
user=> :fooooo:fooooo:fooooo

:fooooo:fooooo:fooooo:fooooo

:fooooo

:fooooo

:fooooo
```

Granted that you can't do much more in a streaming terminal environment.

Clojure 1.8 added a socket repl in `clojure.core.server` that can either be started via command line args or in code to provide network access. It is a very simple way to open a remote REPL but provides no additional features over `clojure.main/repl` otherwise. There is no way to query the state of the server (ie. how many clients are connect? where from? what are they doing?). It is just a simple TCP socket with no support for SSL or anything else really.

## Editor/IDE Integration

Since the above is stream based it is really hard for tools to provide additional features as all they can do is trying to parse the stream of text they get and try to make sense of it. That pretty much will never work.

So most tools instead opt-out and use alternative strategies like `tools.nrepl`. Since that is message based and can support arbitrary messages. `tools.nrepl` however breaks my above definition of a `REPL` as things no longer go over `*in*` and `*out*` and thereby doesn't support nesting multiple REPLs inside each other.

This becomes quickly apparent if you want to start a CLJS REPL inside nREPL. There are ways to make it work but they are very difficult and error prone. It has become better over time but still is way harder than it should be. See the description for [Figwheel](https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl).

Also given the lack of a "standard" in this area, every editor basically re-implements all features they need. There is [cider-nrepl](https://github.com/clojure-emacs/cider-nrepl) and Cursive has its own closed implementation, not sure how much they share but probably not much.


## The Goal

The Goal is to expose more information about the running REPL that a tool can use while keeping the `*in*` and `*out*` model.






