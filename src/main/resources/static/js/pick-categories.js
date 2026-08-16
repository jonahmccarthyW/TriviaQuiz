/*
 * "Pick 6 random categories" for the category checkboxes on a setup form. Shared by the hotseat
 * setup screen and the networked join screen. The server re-validates the selection either way.
 */
function pickRandomCategories(form, count) {
  var boxes = Array.prototype.slice.call(
    form.querySelectorAll('input[name="categories"]'));
  boxes.forEach(function (box) { box.checked = false; });
  // Fisher-Yates shuffle, then check the first `count`.
  for (var i = boxes.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var tmp = boxes[i]; boxes[i] = boxes[j]; boxes[j] = tmp;
  }
  boxes.slice(0, count).forEach(function (box) { box.checked = true; });
}
