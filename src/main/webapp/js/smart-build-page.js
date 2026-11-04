/*
 * FR-5: modern Build With Parameters layout — pure progressive enhancement.
 *
 * The script only ADDS marker classes; it never moves, wraps, or removes DOM nodes, so all
 * existing parameter scripts (smart-params.js, smart-select.js, core hudson-behavior.js)
 * keep working regardless of execution order. Without this script (disabled, 404, or an
 * error before the marker class lands) the page is the untouched native form — FR-5 行为规范 3.
 */
(function () {
  'use strict';

  if (window.__smartBuildPageLoaded) {
    return;
  }
  window.__smartBuildPageLoaded = true;

  var FORM_SELECTOR = 'form[name="parameters"]'; // core's Build With Parameters form

  /**
   * The REAL Build button of the bottom actions bar. Multiple-mode smartSelects used to render
   * a primary button INSIDE the form too — "first primary button" grabbed the widget's panel
   * and the flex actions styling wrecked it. Pick the LAST primary button that does not sit
   * inside a smartSelect widget; core's Build/Cancel bar is the form's last row.
   */
  function actionsBar(form) {
    var buttons = form.querySelectorAll('button.jenkins-button--primary');
    var build = null;
    for (var i = 0; i < buttons.length; i++) {
      var node = buttons[i];
      var insideWidget = false;
      for (var p = node.parentNode; p && p !== form; p = p.parentNode) {
        if (p.getAttribute && p.getAttribute('data-smart-param') === 'select') {
          insideWidget = true;
          break;
        }
      }
      if (!insideWidget) {
        build = node; // keep the last non-widget primary button
      }
    }
    return build;
  }

  function enhance() {
    var form = document.querySelector(FORM_SELECTOR);
    if (!form || form.classList.contains('sbpd-modern')) {
      return; // not a build page, or already enhanced
    }
    form.classList.add('sbpd-modern');

    var cards = form.querySelectorAll('div[name="parameter"]');
    for (var i = 0; i < cards.length; i++) {
      cards[i].classList.add('sbpd-card');
    }

    // The bottom button bar (Build / Cancel) gets its own spacing hook — normal document
    // flow, so it re-positions itself whenever parameter rows are shown/hidden.
    var build = actionsBar(form);
    if (build && build.parentElement) {
      build.parentElement.classList.add('sbpd-actions');
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', enhance);
  } else {
    enhance();
  }
})();
