var x = Buffer.alloc(4);

process.nextTick(function() { console.log(x); });