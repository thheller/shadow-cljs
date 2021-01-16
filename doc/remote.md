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


## Why not nREPL?

`nREPL` was written for CLJ. Several design decisions limit it to CLJ and don't translate well to CLJS (eg. the nrepl session as an atom of Clojure Vars). Makes little sense to keep the nREPL protocol if all message semantics change.

# Architecture

## Relay

A relay just handles receiving messages from and sending to "clients" based on "routing" numbers. A relay is required since not all runtimes can allow direct connections. It is not possible to connect to a Browser remotely, it must first connect to something itself. Therefore by design all "clients" have to connect to one "relay" and it will forward messages and notify of lifecycle events (eg. client connecting or disconnecting).

A relay will `:welcome` each client as the first message if the connection is accepted. Any other first message must be treated as an error and the relay will disconnect if the connection is not accepted.

## Client

The relay only has the notion of clients (previously it separated runtimes and tools). Each client starts out passive and will initiate all actions it wants to do.

The relay expects clients send a `:hello` message containing `:client-info` in response to `:welcome`. It will disconnect the client if `:hello` is not the first reply received over the connection.

Each client can free-form specify a `:client-info` map and clients can query this `:client-info` to discover other clients they may want to talk to. The relay makes no assumptions about the keys/vals in the `:client-info` map, namespace-qualified keywords should be used for custom client interactions.

# The Protocol

The protocol exchanges messages which are simple EDN maps.

The relay itself is implemented in CLJ. The only network protocol currently used is using websockets with transit encoding. Others could be added though. A client may use EDN over TCP to talk to the relay which while other clients still use websockets/transit to communicate with the relay. With access to the CLJ relay instance all communication happens over core.async channels.

Each message MUST contain an `:op` keyword describing it's purpose. Some reserved keywords are used for protocol purposes but each `:op` is free to define any additional keywords.

Reserved keywords include:

- `:op` required keyword for each message
- `:to` either a single number or a set of numbers, set by the client that want to the relay to send messages to the specified runtimes. if `:to` is not set the message is interpreted by the relay.
- `:from` for messages forwarded by the relay from one client to another. if nil the message originated from the relay.
- `:client-id` must be a valid relay client id, meaning depends on `:op` context

## Relay Message Flow

On Connect the relay will send `{:op :welcome :client-id id}`. The client may store its assigned id for later use but does not need to send it since the relay will automatically add the appropriate `:from` to all received messages before forwarding.

On `:welcome` the client must send `:hello`.

- `{:op :hello :client-info {:foo 1}}`

After that the client may start sending messages and will start receiving other messages. This is not RPC, messages may arrive at any time in any order.


### Relay Query

- `{:op :request-clients :query []}`
- result: `{:op :clients :clients [{:client-id 1 :client-info {:foo 1}}]}`

- `{:op :request-notify :notify-op :foo :query []}`
- result: `{:op foo :event-op :client-connect|:client-disconnect :client-id id}`

TBD: describe `:query`

## Tap Message Flow

`tap>` support lets clients listen to other clients that support `tap>`.

- `{:op :tap-subscribe :to id}`
- `{:op :tap-unsubscribe :id id}`

Once subscribed a given runtime may send `:tap` notifications to the subscribed tools via the relay.

- `{:op :tap :from id :oid oid}`

`oid` by default is a random UUID. It must be unique and should be globally unique since a given tool may be talking to different client and having overlapping `:oid` may complicate things. The actual tap value is not included but can be retrieved in different ways later.

## Object Flow

A client may use any `:oid` it received to query for additional info or "views" of that object. The intent is to allow the client to incrementally query the object and maybe navigate from it.

- `{:op :obj-describe :to id :oid oid}`
- result: `{:op :opj-summary :oid oid :summary {...}}`
- not-found: `{:op :opj-not-found :oid oid}`

the client may also perform additional request against a specific oid. Since this is extensible the possible `:request-op` values depends on the `:supports` set from the `:summary`. Additional values in the msg are passed as arguments and each op may require certain keys/values.

- `{:op :obj-request :to id :oid oid :request-op :edn}`
- `{:op :obj-request :to id :oid oid :request-op :str}`
- `{:op :obj-request :to id :oid oid :request-op :pprint}`

- result: `{:op :obj-result :from id :result <request-op-return-value>}` (eg. edn string, pprint string)
- not-found: `{:op :obj-not-found :from id :oid oid}` 
- not supported: `{:op :obj-request-not-supported :oid oid :request-op kw}` client sent `:request-op` kw that isn't supported by the obj.
- fail: `{:op :obj-request-failed :oid oid :ex-oid ex-oid :msg msg}` (use `:ex-oid` to request info about the occured exception)

TBD: define standard `:request-op` and their arguments 

## REPL

TBD

- `:cljs-eval`, `:js-eval`
- `:clj-eval`