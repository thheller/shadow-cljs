var panelWindow = null;

function onShown(window) {
    panelWindow = window;
    chrome.devtools.inspectedWindow.eval("shadow.devtools.enabled", function(result, isException) {
	if (result) {
	    chrome.devtools.inspectedWindow.eval("shadow.devtools.url", function(url, isException) {
		if (!isException) {
		    // do I really need to window.eval?
		    window.eval("shadow.devtools.client.activate('"+ url +"');");
		}
	    });
	}
    });
}

function onHidden() {
    panelWindow.eval("shadow.devtools.client.deactivate();");
}

function setupPanel(panel) {
    panel.onShown.addListener(onShown);
    panel.onHidden.addListener(onHidden);
}

chrome.devtools.panels.create("Shadow",null,"panel.html",setupPanel);
