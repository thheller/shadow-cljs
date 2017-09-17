var x = window["npm$modules"] = {};

x["react"] = require("react");
x["react-dom"] = require("react-dom");

window["require"] = function(name) {
  return x[name];
};