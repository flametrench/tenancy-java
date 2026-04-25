// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import java.time.Instant;

public record Membership(
        String id,
        String usrId,
        String orgId,
        Role role,
        Status status,
        String replaces,
        String invitedBy,
        String removedBy,
        Instant createdAt,
        Instant updatedAt
) {
    public Membership withStatus(Status status, Instant updatedAt) {
        return new Membership(
                id, usrId, orgId, role, status,
                replaces, invitedBy, removedBy,
                createdAt, updatedAt
        );
    }

    public Membership withStatusAndRemovedBy(Status status, String removedBy, Instant updatedAt) {
        return new Membership(
                id, usrId, orgId, role, status,
                replaces, invitedBy, removedBy,
                createdAt, updatedAt
        );
    }
}
