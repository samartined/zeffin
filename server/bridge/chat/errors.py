# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The chat's errors: a request refused with a code (and data) for the app, and a hook call with a wrong token."""
from __future__ import annotations


class ChatError(Exception):
    def __init__(self, code: str, **data):
        super().__init__(code)
        self.code, self.data = code, data


class HookRejected(Exception):
    pass
