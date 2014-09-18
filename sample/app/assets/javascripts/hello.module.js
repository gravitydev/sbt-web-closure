goog.provide('module.hello');

goog.require('goog.dom');
goog.require('stuff.Dialog');

var testDiv = goog.dom.getElement('testing');
testDiv.innerHTML = "HA! Another 22";

var d = new stuff.Dialog();