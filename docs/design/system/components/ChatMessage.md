A message in a conversation with the agent. The user's goes on the right on `info-soft`; the agent's on the left on `surface-raised`, without a border, like the cards. `text` accepts line breaks; the agent's text is Markdown, drawn with style (below). With `streaming` an `info` cursor shows at the end while text arrives. `meta` holds the time or 'interrupted' in `caption`. Bubbles take at most 85% of the width and sit on the black `surface` background.

**The agent's Markdown** (GitHub style; mockup `conversation-12-markdown`):

- Headings by size (level 1 `title`, 2 and 3 `heading`, the rest bold body); paragraphs in `body`; bold, italics and strikethrough.
- Inline code in `mono` on `surface-sunken`, radius 4.
- Tables: 1px `line` borders, the header row bold on `surface-sunken`, cells padded 8×6 with the column's alignment; a wide table scrolls sideways inside the bubble.
- Lists with 22px markers (filled bullets on top, hollow ones nested); task items with a checkbox (checked in `success`).
- Quotes with a 3px `line` bar on the left and `ink-muted` text; separators as a 1px `line`.
- Code blocks on `surface-sunken` with the language and a Copy button on top; `mono`, scrolling sideways.
- Links in `action`, underlined; a tap opens a sheet with the full address, Open in the browser and Copy the link (`conversation-12b-link`). Raw HTML is shown as text and images as their alt text: nothing is fetched from the agent's text.
