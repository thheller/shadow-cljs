goog.provide("shadow.js.babel");

/**
 * @dict
 */
shadow.js.babel = {};

shadow.js.babel["interopRequireDefault"] = function (obj) {
  return obj && obj.__esModule ? obj : {
    "default": obj
  };
};

shadow.js.babel["interopRequireWildcard"] = function (obj) {
  if (obj && obj.__esModule) {
    return obj;
  } else {
    var newObj = {};

    if (obj != null) {
      for (var key in obj) {
        if (Object.prototype.hasOwnProperty.call(obj, key)) newObj[key] = obj[key];
      }
    }

    newObj["default"] = obj;
    return newObj;
  }
};

goog.exportSymbol("shadow.js.babel", shadow.js.babel);