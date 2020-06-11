var goog = global.goog = {};

const SHADOW_GJS_read_module_from_src = function () {
    const { GLib, Gio } = global.imports.gi;
    const ByteArray = global.imports.byteArray;

    return function (src) {
        let filePath = GLib.build_filenamev([SHADOW_IMPORT_PATH, src]);
        let file = Gio.File.new_for_path(filePath);
        let [success, bytes] = file.load_contents(null);
        if (!success) {
            throw Error("SHADOW failed to read file: " + src);
        } else {
            return ByteArray.toString(bytes, 'utf-8');
        }
    };
}();

var SHADOW_IMPORTED = global.SHADOW_IMPORTED = {};

var SHADOW_PROVIDE = function(name) {
  return goog.exportPath_(name, undefined);
};

var SHADOW_REQUIRE = function(name) {
  if (goog.isInModuleLoader_()) {
    return goog.module.getInternal_(name);
  }
  return true;
};

var SHADOW_IMPORT = global.SHADOW_IMPORT = function(src) {
  if (CLOSURE_DEFINES["shadow.debug"]) {
    console.info("SHADOW load:", src);
  }

  SHADOW_IMPORTED[src] = true;
  var code = SHADOW_GJS_read_module_from_src(src);
  eval(code);

  return true;
};
