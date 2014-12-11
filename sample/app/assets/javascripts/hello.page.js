goog.require('goog.dom');
goog.require('goog.array');
goog.require('stuff.Dialog');

var testDiv = goog.dom.getElement('testing');
testDiv.innerHTML = "HA! Another 22";

goog.array.forEach([1,2,3], function (i) {
  console.log(i);
});

var d = new stuff.Dialog();
