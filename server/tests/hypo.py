# SPDX-License-Identifier: AGPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 samartined
"""The settings every property test uses: the same examples on every run (derandomize), a bounded count and no
database folder, so a run is deterministic and takes a few seconds. A failing example is written into the test as an
@example, not kept in .hypothesis/."""
from hypothesis import HealthCheck, settings

EXAMPLES = 150
SETTINGS = settings(derandomize=True, max_examples=EXAMPLES, deadline=None, database=None,
                    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture])
