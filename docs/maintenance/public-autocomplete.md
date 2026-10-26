# R1 public JavaFX autocomplete

Replace the two application TextFields.bindAutoCompletion call sites with PublicAutoCompletion. The popup uses public JavaFX Popup/ListView/TextField APIs, coalesces IME/text events, does not accept intermediate composition text, supports arrows/Enter/Escape and normal Tab traversal, bounds visible suggestions and disposes prior bindings on rebind. SuggestSupport keeps its legacy callback signature for compatibility and adds a string-based query.

The isolated Xvfb workflow checks the actual controller call sites and exercises popup selection, no-match hiding, rebind/close and composition under Java 21 and 25. This is not native Mint/Japanese IME certification. Final Mint visual/input behavior remains deferred. No production installation or data changes.
