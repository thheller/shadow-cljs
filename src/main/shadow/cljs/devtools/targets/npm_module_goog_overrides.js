
goog.global = CLJS_GLOBAL;

goog.provide = function(name) {
  return goog.exportPath_(name, undefined, CLJS_ENV);
};

goog.require = function(name) {
  return true;
};

goog.define = function(name, defaultValue) {
  var value = defaultValue;

  if (Object.prototype.hasOwnProperty.call(CLOSURE_DEFINES, name)) {
    value = CLOSURE_DEFINES[name];
  }

  goog.exportPath_(name, value, CLJS_ENV);
};
