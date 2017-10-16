var X = exports.X = function () {
  this.a = 1;
};

X.prototype.b = function() {
  return this.a;
}

var local = 3;

exports.X = X;
exports.c = 1;

exports.d = {
  e: 2,
  "f": 3
};


var g = { h: {} };

g.h.j = 1;

Object.defineProperty(g, "thing", {});