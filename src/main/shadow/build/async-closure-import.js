var CLOSURE_IMPORT_SCRIPT = (function() {
  if (typeof(window.fetch) === 'undefined') {
    return undefined;
  }

  var loadOrder = [];
  var loadState = {};

  function responseText(response) {
    // FIXME: check status
    return response.text();
  }

  function loadPending() {
    for (var i = 0, len = loadOrder.length; i < len; i++) {
      var uri = loadOrder[i];
      var state = loadState[uri];
      if (typeof(state) === "string") {
        var code = state + "\n//# sourceURL=" + uri + "\n";
        eval(code);
        loadState[uri] = true;
      } else if (state === true) {
        continue;
      } else {
        break;
      }
    }
  }

  function evalFetch(uri) {
    return function(code) {
      loadState[uri] = code;
      loadPending();
    };
  }

  return function closure_fetch(uri) {
    if (loadState[uri] == undefined) {
      loadState[uri] = false;
      loadOrder.push(uri);
      fetch(uri).then(responseText).then(evalFetch(uri));
    }
  };
})();
