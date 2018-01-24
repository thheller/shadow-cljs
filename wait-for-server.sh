#!/bin/sh

until [ -f .shadow-cljs/cli-repl.port ]
do
     echo "."
     sleep 1
done
echo "Server ready!"
exit
