# shadow.remote

New architecture intended for internal uses for now but designed to be accessible by all eventually. Maybe as an alternative to nREPL addressing the CLJS related shortcomings. It is also meant to "solve" some other REPL related issues (eg. the print problem).

Good summary of what this is about is the REBL talk given by Stuard Halloway.

- https://www.youtube.com/watch?v=c52QhiXsmyI

## Print Problem

The P in REPL is a problem if E returned a very large value or something that just isn't printable. Yet most REPL tools presume that they are going to be able to get a good enough representation. I frequently blow up my CLJ REPL in Cursive when I accidentally print the shadow-cljs build-state, which is easily several hundred MB when printed.

## Why not REBL?

REBL solves this neatly **BUT** as far as I can tell can only run inside the Clojure VM itself. It is not designed to be used remotely. Maybe that is still coming in the future but given that it is closed source we can't really tell anything about the internal architecture. The lack of CLJS supports makes REBL unusable for the use-cases I'm building all this for.

It isn't practical to assume that something like the REBL UI is going to be able to run in all possible runtimes (eg. react-native, browser, node-script, something cloud-hosted, etc).

Tooling in Browser-targeted builds is also problematic since they can add really huge amounts of code (eg. re-frame-10x) and the UI might not actually "fit" when using responsive layouts. The tools also don't work then using `react-native`.

The goal is to build something generic that can be used in all possible runtimes. The tools should be providing the UI and run separately or embedded like REBL if wanted. They can talk to the relay remotely or in process.

# Architecture

## Relay

A relay just handles receiving messages from and to endpoints based on "routing" numbers. A relay is required since not all runtimes can allow direct connections. It is not possible to connect to a Browser remotely, it must first connect to something itself. Therefore by design all "runtimes" and "tools" have to connect to one "relay" and it will forward messages and notfiy of lifecycle events (eg. runtime or tool disconnecting).

## Runtime

Anything that is willing and capable to execute commands from "tools". This can be a generic CLJ or CLJS runtime but can also be something way more specific. Each runtime has different capabilities and the protocol should allow negotiating what these are.

## Tool

Anything that wants to talk to "runtimes". Most commonly this will be editors or some kind of other tool UI. Could be command line tools. For the most part it is assumed that these tools will connect remotely to a given Relay.

# The Protocol

The protocol exchanges messages which are simple EDN maps.

The relay itself is implemented in CLJ. The only network protocol currently used is using websockets with transit encoding. Others could be added though. A tool may use EDN over TCP to talk to the relay which the runtimes still uses websockets/transit to commicate with the relay.

Each message MUST contain an `:op` keyword describing it's purpose. Some reserved keywords are used for protocol purposes but each `:op` is free to define any additional keywords.

Reserved keywords include:

- optional `:msg-id` unique identifier for each message sent. Each party is supposed to send this as part of all responses a given msg may trigger.
- `:runtime-id id` set by a tool will tell the relay which runtime to forward the message to
- `:runtime-broadcast true|false` set by a tool to send message to all connected runtimes
- `:tool-id id` set by a runtime to send a message to a specific tool
- `:tool-broadcast true|false` set by a runtime to send message to all connected tools
- if none of these are set the message will be handled by the relay itself

## Relay Message Flow

On Connect the relay will send `{:op :welcome :tool-id id}` or `{:op :welcome :runtime-id id}`. The client may store its assigned id for later use but does not need to send it since the relay will automatically add the `:tool-id` or `:runtime-id` to all received messages before forwarding.

After that the client may start sending messages and will start receiving other messages. This is not RPC, messages may arrive at any time in any order.

These are triggered whenever a runtime connects or leaves and sent to all connected tools. Might make this optional later so that tools have to subscribe to this info first.
- `{:op :runtime-connect :runtime-id 123 :runtime-info {...}}`
- `{:op :runtime-disconnect :runtime-id 123}`

These are triggered when a tool connects or leaves
- `{:op :tool-connect :tool-id 123}`
- `{:op :tool-disconnect :tool-id 123}`

## Tap Message Flow

`tap>` support is first since it is much simpler than a REPL but still makes used of the "P" related features.

A tool can subscribe to a give runtime if it has `tap>` support.

- `{:op :tap-subscribe :runtime-id id}`
- `{:op :tap-unsubscribe :runtime-id id}`

Once subscribed a given runtime may send `:tap` notifications to the subscribed tools via the relay.

- `{:op :tap :obj-id id}`

`id` by default is a random UUID. It must be unique and should be globally unique since a given tool may be talking to different runtimes and having overlapping `:obj-id` may complicate things. The actual tap value is not included in any way.

## Object Flow

A tool may use any `:obj-id` it received to query the runtime for additional info or "views" of that object. The intent is to allow the tool to incrementally "query" the object and maybe "navigate" from it.

- `{:op :obj-request-view :obj-id id :view-type view-type}`
- `{:op :obj-view :obj-id id :view ...}`

`:view-type` should be a keyword, with additional entries in the message for configure that view type if needed.

The defaults should include

- `:edn` resulting in `{:obj :obj-view :view "string repr of the given object"}`
- `:edn-limit` `{:op :request-view :obj-id id :view-type :edn-limit :limit 20}` returning `{... :view [true|false "string limited at :limit chars"]}`. The first boolean inditicates whether a limit was reached or not.

These are still a WIP. Mostly structured this way for UI purposes currently.
- `:summary` `{:data-type :map|:set|:vec|... :obj-type "cljs.core/PersistentArrayMap" :count num}`

Maps are sorted by key and idx refers to their index in the sorted result. Sorting may fail so the summary will include `:sorted true|false`. The tool may request fragments via `:start num` and `:num num`. It should not exceed the previous `:count`. Additionally a `:key-limit num` and `:val-limit num` can be configured to control how much of each value should be attempted to be printed.
 
- `:fragment` `{idx {:key edn-limit :val edn-limit} ...}`

"nav" can only be done by index, typically received in a `:fragment` previously. It is structured this way to avoid actually having to be able to serialize the key. Most of the time that would be fine but sometimes it won't.

- `{:op :obj-nav :obj-id id :idx num}` might at support for actual `:key` at some point
- `{:op :obj-nav-success :obj-id id :nav-obj-id id}` the resulting `:nav-obj-id` can then be queried again like everything else.

The tool can decide whether it wants the "complicated" `:summary|:fragment` logic. It could send a `:edn-limit` request with `1000` or so default and only use the more complicated logic for larger values.

Tools may just request the full `:edn` at any time in which case this would match what regular REPLs do. Other `:view` formats can be added easily (by extending the multi-method in the runtime).


## REPL

TBD, P will just send out an `:obj-id` to be queried as above.