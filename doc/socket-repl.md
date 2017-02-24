# socket-repl thoughts

http://dev.clojure.org/display/design/Socket+Server+REPL

Binds `clojure.core/*in*`, `clojure.core/*out*` and `clojure.core/*err*` to the socket streams.

Binds `clojure.core.server/*session*` on startup with something like `{:server "myrepl", :client "1"}`.

The accept function of the socket can be customized, so it is fairly straightforward to customize what ends up in `*session*`.

# tooling challenges

- Tools want to query the state of the current sessions (eg. for autocomplete)
- want to parse the "result" and a command for syntax highlighting purposes (and other things)
- be able to have more streams than just out/err. (eg. cljs compiler output). If this is interleaved with the normal output on `*out*` things get ugly quick.
- also in when it comes to multiple threads or async output the "prompt" is problematic and IDEs can probably makes this nicer than just printing some text.

# the goal?

full duplex communication between the REPL loop and the tool while not breaking the stream model.



Start a new REPL, socket or not. Bind `*session*` with

```
{:cljs/compiler-warnings
 (fn [warning-data]
   (do-whatever-to-display-warnings warnings-data)}
```


and the loop can

```
(let [x (get *session* :repl/takeover)
      release (x things-i-can-for-you)]
  (try
    (loop []
      (prompt)

      (let [form
            (read)

            [js warnings]
            (cljs-compile form)]

        (if-let [cb (get *session* :cljs/compiler-warnings)]
          (cb warnings)
          (print-warnings warnings))

        (let [result (eval js)]

          (print result)
          (recur))))
    (finally
      (release))))
```





