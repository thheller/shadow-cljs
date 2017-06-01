// this is used by webpack to create a bundle so I can skip :foreign-libs
// the dependencies used by CLJS will be extracted from the manifest

// these are here to replace cljsjs
window["React"] = require("react");
window["ReactDOM"] = require("react-dom");

var x = window["npm$modules"] = {};

window["require"] = function(name) {
  return x[name];
};

// code like this will be appended for each require in cljs code
// x["react"] = require("react")

