if (window["shadow$webpack"] === undefined) {
  window["shadow$webpack"] = {};

  window["require"] = function(name) {
    return window["shadow$webpack"][name];
  };
}
window["shadow$webpack"]["react"] = require("react");