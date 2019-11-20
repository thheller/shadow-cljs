
var originalGoogExportPath = goog.exportPath_;

goog.exportPath_ = function(name, opt_object, opt_objectToExportTo) {
  // must keep the export to global for things like (goog/exportSymbol js/React ...)
  originalGoogExportPath(name, opt_object, opt_objectToExportTo);
  // goog.module.declareLegacyNamespace() otherwise only exports to global but we need it on the $CLJS object
  if (goog.isInModuleLoader_()) {
    originalGoogExportPath(name, opt_object, $CLJS);
  }
}

goog.provide = function(name) {
  return originalGoogExportPath(name, undefined, $CLJS);
};

goog.require = function(name) {
  return goog.getObjectByName(name, $CLJS);
};
