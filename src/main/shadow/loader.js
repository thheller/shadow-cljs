goog.provide("shadow.loader");
goog.require("goog.module.ModuleManager");
goog.require("goog.module.ModuleLoader");

// this is written in JS so it doesn't depend on cljs.core

if (goog.global.shadow$loader) {
  shadow.loader.ml = new goog.module.ModuleLoader();
  shadow.loader.ml.setSourceUrlInjection(true);

  shadow.loader.mm = goog.module.ModuleManager.getInstance();
  shadow.loader.mm.setLoader(shadow.loader.ml);
  shadow.loader.mm.setAllModuleInfo(goog.global.shadow$loader["infos"]);
  shadow.loader.mm.setModuleUris(goog.global.shadow$loader["uris"]);
}

shadow.loader.getModuleManager = function() {
  return shadow.loader.mm;
};

shadow.loader.getModuleLoader = function() {
  return shadow.loader.ml;
};

shadow.loader.set_loaded = function(id) {
  shadow.loader.mm.setLoaded(id);
};

shadow.loader.loaded_QMARK_ = function(id) {
  return shadow.loader.mm.getModuleInfo(id).isLoaded();
};

shadow.loader.with_module = function(
  moduleId,
  fn,
  opt_handler,
  opt_noLoad,
  opt_userInitiated,
  opt_preferSynchronous
) {
  return shadow.loader.mm.execOnLoad(
    moduleId,
    fn,
    opt_handler,
    opt_noLoad,
    opt_userInitiated,
    opt_preferSynchronous
  );
};

shadow.loader.load = function(id, opt_userInitiated) {
  return shadow.loader.mm.load(id, opt_userInitiated);
};

shadow.loader.load_multiple = function(ids, opt_userInitiated) {
  return shadow.loader.mm.loadMultiple(ids, opt_userInitiated);
};

shadow.loader.prefetch = function(id) {
  shadow.loader.mm.prefetch(id);
};

shadow.loader.preload = function(id) {
  return shadow.loader.mm.preload(id);
};

// FIXME: not sure these should always be exported
goog.exportSymbol("shadow.loader.with_module", shadow.loader.with_module);
goog.exportSymbol("shadow.loader.load", shadow.loader.load);
goog.exportSymbol("shadow.loader.load_multiple", shadow.loader.load_multiple);
goog.exportSymbol("shadow.loader.prefetch", shadow.loader.prefetch);
goog.exportSymbol("shadow.loader.preload", shadow.loader.preload);
