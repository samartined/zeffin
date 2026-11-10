# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""Flipper Zero RPC protobuf modules.

proto/ holds the Flipper Zero RPC protocol definitions (see THIRD_PARTY_NOTICES.md); the
*_pb2.py files are generated from them. The generated modules import each other as
top-level modules, so this package exposes its own directory on sys.path while loading
them. Regenerate with grpcio-tools 1.84.1 (protoc 35.1, matching the pinned runtime):

    python -m grpc_tools.protoc -I proto --python_out=. proto/*.proto
"""
import os
import sys

_here = os.path.dirname(os.path.abspath(__file__))
_added = _here not in sys.path
if _added:
    sys.path.insert(0, _here)
try:
    import flipper_pb2 as flipper  # noqa: E402
    import gui_pb2 as gui  # noqa: E402
    import storage_pb2 as storage  # noqa: E402
    import system_pb2 as system  # noqa: E402
    import application_pb2 as application  # noqa: E402
finally:
    if _added:
        sys.path.remove(_here)

Main = flipper.Main
CommandStatus = flipper.CommandStatus

__all__ = ["Main", "CommandStatus", "flipper", "gui", "storage", "system", "application"]
