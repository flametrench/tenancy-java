// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/** Authorization tuple. {@code subjectType} is always {@code "usr"} in v0.1. */
public record Tuple(
        String subjectType,
        String subjectId,
        String relation,
        String objectType,
        String objectId
) {
}
