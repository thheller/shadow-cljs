FROM alpine:3.7

RUN apk update && apk add nodejs-npm openjdk8-jre

RUN npm install -g yarn shadow-cljs
