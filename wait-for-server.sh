#!/bin/sh

until [ -f .shadow-cljs/cli-repl.port ]
do
     echo "."
     sleep 1
done
sleep 1
echo "Server ready!"
exit
