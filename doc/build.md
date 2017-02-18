# shadow.devtools.server.services.build

A dedicated thread for each build. Each of those processes maintains a number of channels used to interact with the build.


# :proc-stop

A channel that will only ever be closed, nothing will ever be written here. This exists so there is something that others can wait for a build process to end.

# :proc-control

A channel expecting control related messages.

# :repl-in

Things to eval go here.

# :repl-result

The results of eval go here.

# :config-updates

When config changes?

# :fs-updates

Receives FS updates from shadow.devtools.server.services.fs-watch.