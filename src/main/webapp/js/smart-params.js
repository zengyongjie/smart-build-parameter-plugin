/*
 * Smart Build Parameter Plugin — progressive enhancement for the Build With Parameters page.
 * Included (idempotently) by every smart parameter's index.jelly; no core templates are touched.
 *
 * FR-1: a smartBoolean controller shows/hides the parameter blocks named in data-controls.
 *   Hidden state = display:none + core's `field-disabled` attribute, so Jenkins' structured
 *   form submission (buildFormTree in hudson-behavior.js) excludes those controls entirely —
 *   hidden parameters are NOT submitted (decision D1).
 *   Phase 1 has no recursive cascading: a parameter's visibility depends only on its own
 *   controller's checkbox state (see docs/REQUIREMENTS.md FR-1, 边界与异常).
 *
 * FR-2 item 3: smartCheckbox groups with data-min-selected > 0 block form submission until the
 *   minimum is met. Hidden groups are exempt from validation (增补 B) — a hidden parameter must
 *   never prevent submission.
 */
(function () {
    "use strict";

    // Marker for tests/diagnostics: proves this file was fetched and executed.
    window.__smartParamsLoaded = true;

    function forEach(list, fn) {
        for (var i = 0; i < list.length; i++) {
            fn(list[i]);
        }
    }

    function trim(s) {
        return s.replace(/^\s+/, "").replace(/\s+$/, "");
    }

    /** Map of parameter name → its <div name="parameter"> block, within the build form. */
    function parameterBlocks(form) {
        var map = {};
        forEach(form.querySelectorAll('div[name="parameter"]'), function (div) {
            var input = div.querySelector('input[name="name"]');
            if (input && input.value) {
                map[input.value] = div;
            }
        });
        return map;
    }

    /**
     * The visible FORM ROW of a parameter: `div[name="parameter"]` is only the control cell
     * inside the enclosing `f:entry` row (`div.jenkins-form-item`, which carries the entry
     * title and description). Hiding just the cell leaves an empty titled row on the page —
     * hide the whole row. Manual ancestor walk: not every engine has Element.closest.
     */
    function entryRow(block) {
        for (var node = block.parentNode; node && node !== document; node = node.parentNode) {
            if (node.getAttribute) {
                var cls = " " + (node.getAttribute("class") || "") + " ";
                if (cls.indexOf(" jenkins-form-item ") >= 0) {
                    return node;
                }
            }
        }
        return block;
    }

    function setHidden(block, hidden) {
        block.setAttribute("data-smart-hidden", hidden ? "true" : "false");
        var row = entryRow(block);
        if (hidden) {
            // Core contract: controls under a `field-disabled` ancestor contribute nothing to
            // the structured-form JSON, so the hidden parameter is not submitted.
            block.setAttribute("field-disabled", "true");
            block.style.display = "none";
            row.style.display = "none";
        } else {
            block.removeAttribute("field-disabled");
            block.style.display = "";
            row.style.display = "";
        }
    }

    /** el = the span[data-smart-param="boolean"]; its checkbox carries the on/off state. */
    function applyController(el, blocks) {
        var checkbox = el.querySelector('input[type="checkbox"]');
        if (!checkbox) {
            return;
        }
        var raw = el.getAttribute("data-controls") || "";
        forEach(raw.split(","), function (name) {
            name = trim(name);
            if (name && blocks[name]) {
                setHidden(blocks[name], !checkbox.checked);
            }
            // Unknown names are ignored (FR-1 边界与异常); validation happens at config time.
        });
    }

    /** True when the element sits inside a block currently hidden by a controller (增补 B). */
    function isInsideHiddenBlock(el, form) {
        for (var node = el; node && node !== form; node = node.parentNode) {
            if (node.getAttribute && node.getAttribute("data-smart-hidden") === "true") {
                return true;
            }
        }
        return false;
    }

    function initControllers(form) {
        var blocks = parameterBlocks(form);
        forEach(form.querySelectorAll('[data-smart-param="boolean"]'), function (el) {
            var checkbox = el.querySelector('input[type="checkbox"]');
            if (!checkbox || el.getAttribute("data-smart-init") === "true") {
                return;
            }
            el.setAttribute("data-smart-init", "true");
            applyController(el, blocks);
            var onChange = function () {
                applyController(el, parameterBlocks(form));
            };
            if (checkbox.addEventListener) {
                checkbox.addEventListener("change", onChange);
                checkbox.addEventListener("click", onChange); // HtmlUnit fires click, not change
            } else if (checkbox.attachEvent) {
                checkbox.attachEvent("onclick", onChange);
            }
        });
    }

    function initMinSelected(form) {
        if (form.getAttribute("data-smart-submit-init") === "true") {
            return;
        }
        form.setAttribute("data-smart-submit-init", "true");

        function validate() {
            var errors = [];
            forEach(form.querySelectorAll('[data-smart-param="checkbox"]'), function (group) {
                if (isInsideHiddenBlock(group, form)) {
                    return; // 增补 B: hidden parameters are exempt from validation.
                }
                var min = parseInt(group.getAttribute("data-min-selected") || "0", 10);
                if (!(min > 0)) {
                    return;
                }
                // Manual count instead of the `:checked` pseudo-selector: not every engine's
                // querySelectorAll supports pseudo-classes (HtmlUnit throws on them).
                var checked = 0;
                forEach(group.querySelectorAll('input[type="checkbox"][name="value"]'), function (box) {
                    if (box.checked) {
                        checked++;
                    }
                });
                if (checked < min) {
                    group.setAttribute("data-smart-error", "true");
                    var message = group.getAttribute("data-min-message");
                    errors.push(message || ("At least " + min + " option(s) must be selected."));
                } else {
                    group.removeAttribute("data-smart-error");
                }
            });
            return errors;
        }

        function veto(ev) {
            var errors = validate();
            if (errors.length > 0) {
                window.__smartVetoCount = (window.__smartVetoCount || 0) + 1; // diagnostic marker
                if (ev && ev.preventDefault) {
                    ev.preventDefault();
                }
                if (ev && ev.stopPropagation) {
                    ev.stopPropagation(); // keep other click handlers from running on a dead submit
                }
                window.alert(errors.join("\n"));
                return false;
            }
        }

        // Primary guard: cancel the click on submit controls before the form machinery runs
        // (some engines, HtmlUnit included, do not dispatch a cancelable `submit` event for
        // button-driven submissions).
        function onClickCapture(ev) {
            var t = ev.target;
            if (!t || !t.getAttribute || !t.tagName) {
                return;
            }
            var tag = t.tagName.toLowerCase();
            var type = (t.getAttribute("type") || "").toLowerCase();
            var isSubmitControl = (tag === "button" && (type === "" || type === "submit"))
                    || (tag === "input" && type === "submit");
            if (isSubmitControl) {
                veto(ev);
            }
        }

        // Secondary guard: submissions raised without a button click (e.g. Enter in a field).
        function onSubmit(ev) {
            veto(ev);
        }

        if (form.addEventListener) {
            form.addEventListener("click", onClickCapture, true);
            form.addEventListener("submit", onSubmit);
        } else if (form.attachEvent) {
            form.attachEvent("onsubmit", onSubmit);
        }
    }

    function init() {
        var form = document.querySelector('form[name="parameters"]');
        if (!form) {
            return;
        }
        initControllers(form);
        initMinSelected(form);
    }

    // The script tag sits inside the form while it is still being parsed, so the controlled
    // blocks may not exist yet: run after the DOM is complete, and again on load as a fallback
    // (both inits are idempotent).
    if (document.addEventListener) {
        document.addEventListener("DOMContentLoaded", init);
        window.addEventListener("load", init);
    } else if (window.attachEvent) {
        window.attachEvent("onload", init);
    }
    if (document.readyState === "complete" || document.readyState === "interactive") {
        init();
    }
}());
