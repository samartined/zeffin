/* @ds-bundle: {"format":4,"namespace":"Zeffin","components":[{"name":"Button"},{"name":"StateText"},{"name":"InfoCard"},{"name":"ActionRow"},{"name":"SettingRow"},{"name":"TabSwitch"},{"name":"StatusBar"},{"name":"FlipperScreen"},{"name":"GateControls"},{"name":"ChatMessage"},{"name":"ToolCard"},{"name":"DeviceCard"},{"name":"TextField"},{"name":"Banner"},{"name":"Sheet"}]} */
(function () {
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  var SVG = 'http://www.w3.org/2000/svg';
  /* Small stroke icons used by the previews; the app uses Material Icons. */
  var ICONS = {
    chevron: 'M9 6l6 6-6 6',
    sync: 'M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5',
    stop: 'M6 6h12v12H6z',
    open: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6',
    plus: 'M12 5v14M5 12h14',
    pin: 'M9 4h6l-1 5 3 3v2H7v-2l3-3-1-5zM12 14v6',
    star: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z',
    pencil: 'M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4',
    copy: 'M9 9h11v11H9zM5 15V5h10',
    flipper: 'M6.5 6.5h11a4.5 4.5 0 0 1 0 9h-11a4.5 4.5 0 0 1 0-9zM5.5 9h7v4h-7z',
    bluetooth: 'M7 7l10 10-5 5V2l5 5L7 17'
  };
  function icon(name, cls) {
    var s = document.createElementNS(SVG, 'svg');
    s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '2'); s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
    s.setAttribute('aria-hidden', 'true');
    if (cls) s.setAttribute('class', cls);
    var p = document.createElementNS(SVG, 'path'); p.setAttribute('d', ICONS[name] || ICONS.chevron); s.appendChild(p);
    return s;
  }
  function state(kind, text) { return el('span', 'fb-state' + (kind ? ' fb-state--' + kind : ''), text); }
  function cardHead(card, title, end) {
    var h = el('div', 'fb-card__head');
    h.appendChild(el('div', 'fb-card__title', title));
    if (end) h.appendChild(end);
    card.appendChild(h);
  }

  /* Draws a 128x64 Flipper framebuffer (1024 bytes, vertical byte packing, LSB on top). */
  function drawFrame(canvas, bytes, scale) {
    canvas.width = 128 * scale; canvas.height = 64 * scale;
    var ctx = canvas.getContext('2d');
    var styles = getComputedStyle(canvas);
    ctx.fillStyle = styles.getPropertyValue('--screen-paper').trim() || '#ff8c29';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = styles.getPropertyValue('--screen-pixel').trim() || '#000000';
    for (var page = 0; page < 8; page++) for (var x = 0; x < 128; x++) {
      var b = bytes[page * 128 + x];
      if (!b) continue;
      for (var bit = 0; bit < 8; bit++) if (b & (1 << bit)) ctx.fillRect(x * scale, (page * 8 + bit) * scale, scale, scale);
    }
  }

  /* Demo framebuffer: a menu rendered with a 5x7 pixel font, so previews need no device. */
  var FONT = {
    'A': [0x7e,0x11,0x11,0x7e], 'B': [0x7f,0x49,0x49,0x36], 'C': [0x3e,0x41,0x41,0x22], 'D': [0x7f,0x41,0x41,0x3e], 'E': [0x7f,0x49,0x49,0x41],
    'F': [0x7f,0x09,0x09,0x01], 'G': [0x3e,0x41,0x51,0x72], 'H': [0x7f,0x08,0x08,0x7f], 'I': [0x41,0x7f,0x41], 'J': [0x20,0x40,0x41,0x3f],
    'K': [0x7f,0x08,0x14,0x63], 'L': [0x7f,0x40,0x40,0x40], 'M': [0x7f,0x02,0x04,0x02,0x7f], 'N': [0x7f,0x04,0x08,0x7f], 'O': [0x3e,0x41,0x41,0x3e],
    'P': [0x7f,0x09,0x09,0x06], 'Q': [0x3e,0x41,0x61,0x7e], 'R': [0x7f,0x09,0x19,0x66], 'S': [0x46,0x49,0x49,0x31], 'T': [0x01,0x7f,0x01],
    'U': [0x3f,0x40,0x40,0x3f], 'V': [0x1f,0x20,0x40,0x20,0x1f], 'W': [0x7f,0x20,0x18,0x20,0x7f], 'X': [0x63,0x14,0x08,0x14,0x63], 'Y': [0x07,0x08,0x70,0x08,0x07],
    'Z': [0x61,0x51,0x49,0x45,0x43], '0': [0x3e,0x51,0x49,0x45,0x3e], '1': [0x42,0x7f,0x40], '2': [0x62,0x51,0x49,0x46], '5': [0x27,0x45,0x45,0x39],
    '-': [0x08,0x08,0x08], ' ': [0x00,0x00], '.': [0x40], ':': [0x24], '>': [0x41,0x22,0x14,0x08]
  };
  function demoFrame(title, items, selected) {
    var bytes = new Uint8Array(1024);
    function px(x, y, on) { if (x < 0 || x > 127 || y < 0 || y > 63) return; var i = (y >> 3) * 128 + x; if (on) bytes[i] |= 1 << (y & 7); else bytes[i] &= ~(1 << (y & 7)); }
    function text(x, y, s, inverted) {
      s = s.toUpperCase();
      for (var c = 0; c < s.length; c++) {
        var g = FONT[s[c]] || FONT[' '];
        for (var col = 0; col < g.length; col++) for (var row = 0; row < 7; row++) {
          var on = (g[col] >> row) & 1; px(x + col, y + row, inverted ? !on : on);
        }
        x += g.length + 1;
      }
    }
    text(2, 1, title);
    for (var x = 0; x < 128; x++) px(x, 10, true);
    for (var i = 0; i < items.length && i < 4; i++) {
      var y = 14 + i * 12;
      if (i === selected) { for (var xx = 0; xx < 128; xx++) for (var yy = y - 2; yy < y + 9; yy++) px(xx, yy, true); text(4, y, items[i], true); }
      else text(4, y, items[i], false);
    }
    return bytes;
  }

  var Zeffin = {
    demoFrame: demoFrame,

    Button: function (p) {
      var b = el('button', 'fb fb-btn fb-btn--' + (p.variant || 'primary') + (p.small ? ' fb-btn--small' : ''), p.label);
      b.type = 'button';
      if (p.disabled) b.disabled = true;
      return b;
    },

    StateText: function (p) { var s = state(p.kind, p.text); s.className += ' fb'; return s; },

    InfoCard: function (p) {
      var c = el('div', 'fb fb-card');
      if (p.title) cardHead(c, p.title, p.state ? state(p.state.kind, p.state.text) : null);
      (p.rows || []).forEach(function (r) {
        var row = el('div', 'fb-info');
        row.appendChild(el('span', 'fb-info__label', r.label));
        var v = el('span', 'fb-info__value' + (r.mono ? ' fb-info__value--mono' : ''), r.value);
        if (r.kind) v.className += ' fb-state--' + r.kind;
        row.appendChild(v);
        c.appendChild(row);
      });
      (p.actions || []).forEach(function (a) { c.appendChild(Zeffin.ActionRow(a)); });
      return c;
    },

    ActionRow: function (p) {
      var b = el('button', 'fb-action' + (p.danger ? ' fb-action--danger' : ''));
      b.type = 'button';
      b.appendChild(icon(p.icon || 'sync'));
      b.appendChild(document.createTextNode(p.label));
      return b;
    },

    SettingRow: function (p) {
      var r = el(p.toggle ? 'div' : 'button', 'fb-setting');
      if (!p.toggle) r.type = 'button';
      var t = el('span', 'fb-setting__text');
      t.appendChild(el('span', 'fb-setting__title', p.title));
      if (p.description) t.appendChild(el('span', 'fb-setting__desc', p.description));
      r.appendChild(t);
      if (p.value) r.appendChild(el('span', 'fb-setting__value', p.value));
      if (p.toggle) {
        var s = el('button', 'fb-switch' + (p.on !== false ? ' is-on' : ''));
        s.type = 'button'; s.setAttribute('role', 'switch'); s.setAttribute('aria-checked', String(p.on !== false)); s.setAttribute('aria-label', p.title);
        r.appendChild(s);
      } else r.appendChild(icon('chevron', 'fb-setting__chev'));
      return r;
    },

    TabSwitch: function (p) {
      var t = el('div', 'fb fb-tabs');
      t.setAttribute('role', 'tablist');
      (p.tabs || []).forEach(function (name, i) {
        var b = el('button', i === (p.active || 0) ? 'is-active' : '', name);
        b.type = 'button'; b.setAttribute('role', 'tab'); b.setAttribute('aria-selected', String(i === (p.active || 0)));
        t.appendChild(b);
      });
      return t;
    },

    StatusBar: function (p) {
      var bar = el('div', 'fb fb-status');
      bar.appendChild(el('span', 'fb-status__name', p.name || 'Flipper'));
      var relay = { active: 'Connected over ' + (p.transport || 'BLE'), connecting: 'Connecting', stopped: 'Relay stopped', error: 'Relay error' }[p.relay || 'stopped'];
      var agent = { active: 'agent active', paused: 'agent paused', stopped: 'agent stopped', busy: 'the agent is writing' }[p.agent || 'active'];
      var parts = [relay, agent];
      if (p.screenAge != null && p.screenAge > 5) parts.push('screen from ' + p.screenAge + ' s ago');
      else if (p.seq != null) parts.push('#' + p.seq);
      bar.appendChild(el('span', 'fb-status__state', parts.join(' · ')));
      return bar;
    },

    FlipperScreen: function (p) {
      var scale = p.scale || 3;
      var box = el('div', 'fb fb-screen' + (p.stale ? ' fb-screen--stale' : ''));
      var canvas = el('canvas');
      box.appendChild(canvas);
      drawFrame(canvas, p.frame || demoFrame('Main menu', ['Sub-GHz', '125 kHz RFID', 'NFC', 'Infrared'], 1), scale);
      if (p.stale) box.appendChild(el('span', 'fb-screen__badge', p.staleText || 'Stale screen'));
      return box;
    },

    GateControls: function (p) {
      var g = el('div', 'fb fb-gate');
      var active = (p.state || 'active') === 'active';
      g.appendChild(Zeffin.Button({ label: 'Pause agent', variant: 'warning', disabled: !active }));
      g.appendChild(Zeffin.Button({ label: 'Resume', variant: 'success', disabled: active }));
      return g;
    },

    ChatMessage: function (p) {
      var m = el('div', 'fb fb-msg fb-msg--' + (p.role || 'assistant'));
      var bubble = el('div', 'fb-msg__bubble', p.text || '');
      if (p.streaming) bubble.appendChild(el('span', 'fb-msg__cursor'));
      m.appendChild(bubble);
      if (p.meta) m.appendChild(el('div', 'fb-msg__meta', p.meta));
      return m;
    },

    ToolCard: function (p) {
      var c = el('div', 'fb fb-tool' + (p.error ? ' fb-tool--error' : ''));
      var thumb = el('div', 'fb-tool__thumb');
      var canvas = el('canvas');
      thumb.appendChild(canvas);
      drawFrame(canvas, p.frame || demoFrame('Main menu', ['Sub-GHz', '125 kHz RFID', 'NFC', 'Infrared'], 2), 1);
      c.appendChild(thumb);
      var body = el('div');
      body.appendChild(el('div', 'fb-tool__title', p.tool || 'flipper_press'));
      body.appendChild(el('div', 'fb-tool__text', p.text || ''));
      if (p.meta) body.appendChild(el('div', 'fb-tool__meta', p.meta));
      c.appendChild(body);
      return c;
    },

    DeviceCard: function (p) {
      var r = el('div', 'fb fb-device');
      var kind = el('div', 'fb-device__kind');
      kind.appendChild(icon(p.likely ? 'flipper' : 'bluetooth'));
      kind.appendChild(document.createTextNode(p.likely ? 'Flipper' : 'Other'));
      r.appendChild(kind);
      var body = el('div', 'fb-device__body');
      body.appendChild(el('span', 'fb-device__name', p.name || '(no name)'));
      body.appendChild(el('span', 'fb-device__addr', p.address || ''));
      r.appendChild(body);
      var end = el('div', 'fb-device__end');
      end.appendChild(p.current ? state('action', 'In use') : Zeffin.Button({ label: 'Use', small: true }));
      r.appendChild(end);
      return r;
    },

    TextField: function (p) {
      var f = el('div', 'fb fb-field' + (p.error ? ' fb-field--error' : ''));
      f.appendChild(el('label', 'fb-field__label', p.label || ''));
      var input = el('input', 'fb-field__input' + (p.mono ? ' fb-field__input--mono' : ''));
      input.type = p.secret ? 'password' : 'text';
      input.value = p.value || '';
      input.placeholder = p.placeholder || '';
      f.appendChild(input);
      if (p.hint || p.error) f.appendChild(el('div', 'fb-field__hint', p.error || p.hint));
      return f;
    },

    Sheet: function (p) {
      var wrap = el('div', 'fb');
      wrap.style.position = 'relative'; wrap.style.height = (p.height || 320) + 'px'; wrap.style.overflow = 'hidden';
      wrap.appendChild(el('div', 'fb-scrim'));
      var s = el('section', 'fb-sheet');
      s.appendChild(el('span', 'fb-sheet__handle'));
      var h = el('div', 'fb-sheet__head');
      h.appendChild(el('span', 'fb-sheet__title', p.title || ''));
      if (p.subtitle) h.appendChild(el('span', 'fb-sheet__sub', p.subtitle));
      s.appendChild(h);
      (p.settings || []).forEach(function (r) { s.appendChild(Zeffin.SettingRow(r)); });
      (p.actions || []).forEach(function (a) { s.appendChild(Zeffin.ActionRow(a)); });
      if (p.button) s.appendChild(Zeffin.Button({ label: p.button }));
      wrap.appendChild(s);
      return wrap;
    },

    Banner: function (p) {
      var b = el('div', 'fb fb-banner fb-banner--' + (p.kind || 'info'));
      b.appendChild(el('div', 'fb-banner__title', p.title || ''));
      if (p.text) b.appendChild(el('div', null, p.text));
      if (p.detail) b.appendChild(el('div', 'fb-banner__detail', p.detail));
      return b;
    }
  };
  window.Zeffin = Zeffin;
})();
