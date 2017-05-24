
goog.provide = function(name) {
  return goog.exportPath_(name, undefined, CLJS_ENV);
};

goog.require = function(name) {
  return true;
};

// goog.define exports to global by default
// we need it on CLJS_ENV
goog.define = function(name, defaultValue) {
  var value = defaultValue;

  if (Object.prototype.hasOwnProperty.call(CLOSURE_DEFINES, name)) {
    value = CLOSURE_DEFINES[name];
  }

  goog.exportPath_(name, value, CLJS_ENV);
};
