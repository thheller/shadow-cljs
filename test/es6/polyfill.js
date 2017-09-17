// ES6 things that require polyfills


// https://developer.mozilla.org/en/docs/Web/JavaScript/Reference/Global_Objects/Promise

function myAsyncFunction(url) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("GET", url);
    xhr.onload = () => resolve(xhr.responseText);
    xhr.onerror = () => reject(xhr.statusText);
    xhr.send();
  });
};

myAsyncFunction("http://www.clojurescript.org").then(function(res) { console.log(res); })


var x = new Map();

console.log("x", x);

var y = new Set();

console.log("y", y);


/** @constructor */
var a = function() {
  this.foo = true;
}

console.log(new a());


export { x, y, myAsyncFunction as z };
