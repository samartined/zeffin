An effort gauge inside the conversation settings sheet: five segments on a half circle, from Low (left, "Faster") to Max (right, "Deeper"). The segments up to the chosen level are `accent`, the rest `line`; a white rounded-square handle sits on the chosen segment. The level name is centered under the arc in 22px, and one gray line below says what the level does.

- Tap a segment or drag the handle; the sheet's subtitle says the change applies from the next message.
- While dragging, the handle grows and the chosen segment glows.
- Always a concrete level: never a "default" state or word. Levels the model does not accept are not drawn as choices.
- Accessibility: `role="slider"` with the level as its value text; on Android, a slider semantics node with five steps.
- Compose: `EffortDial` in `ui/components/EffortDial.kt`. Captures `home-06-settings` and `home-06c-effort`.
