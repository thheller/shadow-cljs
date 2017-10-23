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

  // apparently calling eval causes the code to never be optimized
  // creating a script node, appending it and removing it seems to
  // have the same effect without the speed penalty?
  function scriptEval(code) {
    var node = document.createElement("script");
    node.appendChild(document.createTextNode(code));
    document.body.append(node);
    document.body.removeChild(node);
  }

  function loadPending() {
    var loadNow = "";
    for (var i = 0, len = loadOrder.length; i < len; i++) {
      var uri = loadOrder[i];
      var state = loadState[uri];
      if (typeof(state) === "string") {
        if (state != "") {
          var code = state + "\n//# sourceURL=" + uri + "\n";
          scriptEval(code);
        }
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
