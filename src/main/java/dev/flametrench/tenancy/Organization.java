// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import java.time.Instant;

/**
 * v0.2 (ADR 0011): {@code name} and {@code slug} are optional metadata
 * fields. Both nullable; pre-v0.2 callers may construct via the v0.1
 * 4-arg helper which leaves them null.
 */
public record Organization(
        String id,
        Status status,
        Instant createdAt,
        Instant updatedAt,
        String name,
        String slug
) {
    /** v0.1-compatible constructor: name and slug default to null. */
    public Organization(String id, Status status, Instant createdAt, Instant updatedAt) {
        this(id, status, createdAt, updatedAt, null, null);
    }

    public Organization withStatus(Status status, Instant updatedAt) {
        return new Organization(id, status, createdAt, updatedAt, name, slug);
    }

    public Organization withMetadata(String name, String slug, Instant updatedAt) {
        return new Organization(id, status, createdAt, updatedAt, name, slug);
    }
}
