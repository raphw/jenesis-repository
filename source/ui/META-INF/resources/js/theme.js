/*
 * theme.js — the shared light/dark theme switch for both consoles.
 *
 * Pico already themes by the `data-theme` attribute on <html> and falls back to the OS preference
 * (prefers-color-scheme) when the attribute is absent, and app.css defines its status tokens for both. This script
 * only adds the user's explicit choice on top: it applies a stored override at parse time (the shell loads it
 * without `defer`, so the first paint is already in the chosen theme - no flash), and wires any control marked
 * [data-theme-select] to change and persist it. "auto" removes the override, handing the decision back to the OS.
 */
(function () {
    var stored = null;
    try {
        stored = localStorage.getItem('jenesis-theme');
    } catch (ignored) {
        // Storage can be unavailable (privacy mode); the console then simply follows the OS preference.
    }
    if (stored === 'light' || stored === 'dark') {
        document.documentElement.setAttribute('data-theme', stored);
    }
    document.addEventListener('DOMContentLoaded', function () {
        Array.prototype.forEach.call(document.querySelectorAll('[data-theme-select]'), function (select) {
            select.value = stored === 'light' || stored === 'dark' ? stored : 'auto';
            select.addEventListener('change', function () {
                var choice = select.value;
                try {
                    if (choice === 'light' || choice === 'dark') {
                        localStorage.setItem('jenesis-theme', choice);
                    } else {
                        localStorage.removeItem('jenesis-theme');
                    }
                } catch (ignored) {
                }
                if (choice === 'light' || choice === 'dark') {
                    document.documentElement.setAttribute('data-theme', choice);
                } else {
                    document.documentElement.removeAttribute('data-theme');
                }
            });
        });
    });
})();
