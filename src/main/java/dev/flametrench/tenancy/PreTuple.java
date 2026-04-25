// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/**
 * A resource-scoped grant pre-declared on an invitation. Materialized as
 * a {@code tup_} row at accept time with the accepting user as subject.
 */
public record PreTuple(
        String relation,
        String objectType,
        String objectId
) {
}
