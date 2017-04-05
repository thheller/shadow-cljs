goog.provide("shadow.loader");
goog.require("goog.module.ModuleManager");
goog.require("goog.module.ModuleLoader");

shadow.loader.ml = new goog.module.ModuleLoader();

goog.events.listen(shadow.loader.ml, goog.module.ModuleLoader.EventType.REQUEST_ERROR, function(e) { console.log("error", e); })

shadow.loader.mm = goog.module.ModuleManager.getInstance();
shadow.loader.mm.setLoader(shadow.loader.ml);

shadow.loader.setup = function(uris, modules) {
  shadow.loader.mm.setAllModuleInfo(modules);
  shadow.loader.mm.setModuleUris(uris);
};

shadow.loader.set_loaded = function(id) {
  shadow.loader.mm.setLoaded(id);
};

shadow.loader.loaded_QMARK_ = function(id) {
  return shadow.loader.mm.getModuleInfo(id).isLoaded();
}

shadow.loader.with_module = function(moduleId, fn, opt_handler, opt_noLoad, opt_userInitiated, opt_preferSynchronous) {
  shadow.loader.mm.execOnLoad(moduleId, fn, opt_handler, opt_noLoad, opt_userInitiated, opt_preferSynchronous);
};

goog.exportSymbol("shadow.loader.with_module", shadow.loader.with_module);