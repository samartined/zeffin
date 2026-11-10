An attachment chip: a 44px thumbnail (images) or a file icon on `surface-sunken`, the name in 13px `ink` and one gray line with the type or state and the size. 1px `line` border, `radius-md`, at most 200px wide; long names end with an ellipsis.

- **Before sending** (the tray above the composer): a remove button in the corner and, while uploading, a 3px `action` bar along the bottom. The send button waits for the uploads.
- **In a sent message:** images are shown larger (132px wide, tap to open), Flipper captures on their orange paper, other files as chips without the remove button. They sit above the bubble, aligned with it.
- **The clip** is inside the text field, on its right, in `ink-muted`; it opens the Attach sheet: four large buttons in a 2x2 grid, Take photo (the phone's camera app), Gallery, File and Flipper screen (the screen shown on Home, as a PNG), with a note on the limits.
- Compose: `AttachmentChip` in `ui/screens/Chat.kt`; `Tray`, `MessageAttachment`, `AttachSheet` and `ImageViewer` in `ui/screens/AttachUi.kt`. Captures `home-10-attach` and `home-11-attachments`.
