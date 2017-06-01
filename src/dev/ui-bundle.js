// this is used by webpack to create a bundle so I can skip :foreign-libs
// the dependencies used by CLJS will be extracted from the manifest
// and appended here

var React = require("react");
var ReactDOM = require("react-dom");

// these are here to replace cljsjs
window["React"] = React;
window["ReactDOM"] = ReactDOM;

var x = window["npm$modules"] = {};

window["require"] = function(name) {
  return x[name];
};
