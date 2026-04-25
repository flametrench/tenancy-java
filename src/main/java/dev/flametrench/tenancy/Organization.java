// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import java.time.Instant;

public record Organization(
        String id,
        Status status,
        Instant createdAt,
        Instant updatedAt
) {
    public Organization withStatus(Status status, Instant updatedAt) {
        return new Organization(id, status, createdAt, updatedAt);
    }
}
