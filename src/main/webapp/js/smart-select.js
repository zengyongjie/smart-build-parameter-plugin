/*
 * Smart Build Parameter Plugin — FR-3/FR-4 smartSelect dropdown widget for the Build With
 * Parameters page. Progressive enhancement over real form controls:
 *
 *   - single mode:  radio inputs named `value` (one submitted string)
 *   - multiple:     checkboxes named `value` with a `json` attribute (JSONArray, FR-2 contract)
 *   - filterable:   case-insensitive substring filter, purely client-side (decision D4)
 *   - source error: inline message + retry that re-fetches options via the descriptor
 *                   (FR-3 行为规范 4). While in error state the parameter renders no value
 *                   control, so it cannot be submitted.
 *
 * Idempotent: included by every smartSelect index.jelly; safe to load repeatedly.
 */
(function () {
    "use strict";

    window.__smartSelectLoaded = true; // marker for tests/diagnostics

    function forEach(list, fn) {
        for (var i = 0; i < list.length; i++) {
            fn(list[i]);
        }
    }

    function trim(s) {
        return s.replace(/^\s+/, "").replace(/\s+$/, "");
    }

    /** The parameter's own name lives in the enclosing div[name="parameter"] block. */
    function parameterName(root) {
        var block = root;
        while (block && !(block.getAttribute && block.getAttribute("name") === "parameter")) {
            block = block.parentNode;
        }
        var input = block && block.querySelector('input[name="name"]');
        return input ? input.value : "";
    }

    /** Job full name from the page URL ("/job/a/job/b/build" → "a/b"); "" outside a job. */
    function jobFullName() {
        var root = (document.head && document.head.getAttribute("data-rooturl")) || "";
        var path = location.pathname;
        if (root && path.indexOf(root) === 0) {
            path = path.substring(root.length);
        }
        var prefix = (path.match(/^(?:job\/[^/]+\/)+/) || [""])[0];
        var names = [];
        var re = /job\/([^/]+)/g;
        var m;
        while ((m = re.exec(prefix)) !== null) {
            names.push(decodeURIComponent(m[1]));
        }
        return names.join("/");
    }

    function isMultiple(root) {
        return root.getAttribute("data-mode") === "multiple";
    }

    function toggleList(root, open) {
        var list = root.querySelector(".smart-select-list");
        var box = root.querySelector(".smart-select-box");
        if (!list || !box) {
            return;
        }
        list.style.display = open ? "" : "none";
        box.setAttribute("aria-expanded", open ? "true" : "false");
        if (open) {
            var f = root.querySelector(".smart-select-filter");
            if (f) {
                f.focus();
            }
        }
    }

    function isOpen(root) {
        var list = root.querySelector(".smart-select-list");
        return !!list && list.style.display !== "none";
    }

    /** Echo the current selection into the closed box (joined with the delimiter, D2). */
    function updateBox(root) {
        var box = root.querySelector(".smart-select-value");
        if (!box) {
            return;
        }
        var delimiter = root.getAttribute("data-delimiter") || ",";
        var checked = [];
        forEach(root.querySelectorAll('input[name="value"]'), function (input) {
            if (input.checked) {
                checked.push(input.getAttribute("value"));
            }
        });
        box.textContent = checked.join(delimiter);
    }

    /** D4: client-side, case-insensitive substring filter over the option labels. */
    function applyFilter(root) {
        var filterEl = root.querySelector(".smart-select-filter");
        var q = filterEl ? trim(filterEl.value).toLowerCase() : "";
        forEach(root.querySelectorAll(".smart-select-option"), function (label) {
            var text = (label.textContent || "").toLowerCase();
            label.style.display = (!q || text.indexOf(q) >= 0) ? "" : "none";
        });
    }

    function attachHandlers(root) {
        var box = root.querySelector(".smart-select-box");
        if (box) {
            box.addEventListener("click", function (ev) {
                ev.preventDefault();
                toggleList(root, !isOpen(root));
            });
        }

        var filterEl = root.querySelector(".smart-select-filter");
        if (filterEl) {
            forEach(["input", "keyup", "change"], function (evt) {
                filterEl.addEventListener(evt, function () {
                    applyFilter(root);
                });
            });
            // Esc in the filter closes the dropdown (conventional combobox behavior).
            filterEl.addEventListener("keydown", function (ev) {
                if (ev && ev.keyCode === 27) {
                    toggleList(root, false);
                }
            });
        }

        // Multiple mode applies selections live (updateBox runs on every change) — there is
        // deliberately NO confirm button; the dropdown closes on outside click or Esc.
        forEach(root.querySelectorAll('input[name="value"]'), function (input) {
            function onChange() {
                updateBox(root);
                if (!isMultiple(root)) {
                    toggleList(root, false); // radio semantics: pick → collapse
                }
            }
            input.addEventListener("change", onChange);
            input.addEventListener("click", onChange); // HtmlUnit fires click, not change
        });

        var retry = root.querySelector(".smart-select-retry");
        if (retry) {
            retry.addEventListener("click", function (ev) {
                ev.preventDefault();
                reloadOptions(root);
            });
        }
    }

    /**
     * Close any open dropdown when the click lands outside it (or Esc is pressed anywhere) —
     * the panel is absolutely positioned, so an open panel must never linger over the rest
     * of the form (Build/Cancel included). One document-level listener for all widgets.
     */
    function initGlobalDismiss() {
        if (window.__smartSelectDismiss) {
            return;
        }
        window.__smartSelectDismiss = true;
        document.addEventListener("click", function (ev) {
            var t = ev.target;
            forEach(document.querySelectorAll('div[data-smart-param="select"]'), function (root) {
                var list = root.querySelector(".smart-select-list");
                if (list && list.style.display !== "none" && t && root.contains && !root.contains(t)) {
                    toggleList(root, false);
                }
            });
        });
        document.addEventListener("keydown", function (ev) {
            if (!ev || ev.keyCode !== 27) {
                return;
            }
            forEach(document.querySelectorAll('div[data-smart-param="select"]'), function (root) {
                toggleList(root, false);
            });
        });
    }

    function makeOption(root, option) {
        var label = document.createElement("label");
        label.className = "smart-select-option";
        var input = document.createElement("input");
        input.setAttribute("type", isMultiple(root) ? "checkbox" : "radio");
        input.setAttribute("name", "value");
        input.setAttribute("value", option);
        input.setAttribute("json", option); // FR-2 structured-form contract
        var span = document.createElement("span");
        span.appendChild(document.createTextNode(option));
        label.appendChild(input);
        label.appendChild(span);
        return label;
    }

    /** Replace the error state with a working widget built from freshly fetched options. */
    function buildWidget(root, options) {
        var errorDiv = root.querySelector(".smart-select-error");
        if (errorDiv) {
            errorDiv.parentNode.removeChild(errorDiv);
        }
        forEach(root.querySelectorAll(".smart-select-box, .smart-select-list"), function (el) {
            el.parentNode.removeChild(el);
        });

        var box = document.createElement("button");
        box.setAttribute("type", "button");
        box.className = "jenkins-button smart-select-box";
        box.setAttribute("aria-expanded", "false");
        var value = document.createElement("span");
        value.className = "smart-select-value";
        box.appendChild(value);
        root.appendChild(box);

        var list = document.createElement("div");
        list.className = "smart-select-list";
        list.style.display = "none";
        if (root.getAttribute("data-filterable") !== "false") {
            var filter = document.createElement("input");
            filter.setAttribute("type", "text");
            filter.className = "smart-select-filter";
            filter.setAttribute("placeholder", root.getAttribute("data-filter-placeholder") || "");
            filter.setAttribute("autocomplete", "off");
            list.appendChild(filter);
        }
        var optionsDiv = document.createElement("div");
        optionsDiv.className = "smart-select-options";
        if (!options || !options.length) {
            var empty = document.createElement("div");
            empty.className = "smart-select-empty";
            empty.appendChild(document.createTextNode(root.getAttribute("data-no-options") || ""));
            optionsDiv.appendChild(empty);
        } else {
            forEach(options, function (opt) {
                optionsDiv.appendChild(makeOption(root, String(opt)));
            });
        }
        list.appendChild(optionsDiv);
        root.appendChild(list);
        attachHandlers(root);
    }

    /** FR-3 行为规范 4 "retry": re-run the source server-side, swap in fresh options. */
    function reloadOptions(root) {
        var url = root.getAttribute("data-retry-url");
        if (!url) {
            return;
        }
        var query = "?job=" + encodeURIComponent(jobFullName())
                + "&name=" + encodeURIComponent(parameterName(root));
        root.setAttribute("data-smart-retrying", "true");
        window.fetch(url + query).then(function (resp) {
            return resp.json();
        }).then(function (payload) {
            var data = payload && payload.data ? payload.data : payload;
            if (data.options) {
                buildWidget(root, data.options);
            } else {
                var err = root.querySelector(".smart-select-error span.error");
                if (err) {
                    err.textContent = data.error || "retry failed";
                }
            }
        })["catch"](function () {
            var err = root.querySelector(".smart-select-error span.error");
            if (err) {
                err.textContent = "retry failed";
            }
        })["finally"](function () {
            if (root.removeAttribute) {
                root.removeAttribute("data-smart-retrying");
            }
        });
    }

    function init() {
        forEach(document.querySelectorAll('div[data-smart-param="select"]'), function (root) {
            if (root.getAttribute("data-smart-select-init") === "true") {
                return;
            }
            root.setAttribute("data-smart-select-init", "true");
            attachHandlers(root);
        });
        initGlobalDismiss();
    }

    if (document.addEventListener) {
        document.addEventListener("DOMContentLoaded", init);
        window.addEventListener("load", init);
    }
    if (document.readyState === "complete" || document.readyState === "interactive") {
        init();
    }
}());
