// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

import java.time.Instant;
import java.util.List;

public record Invitation(
        String id,
        String orgId,
        String identifier,
        Role role,
        InvitationStatus status,
        List<PreTuple> preTuples,
        String invitedBy,
        String invitedUserId,
        Instant createdAt,
        Instant expiresAt,
        Instant terminalAt,
        String terminalBy
) {
    public Invitation transitionTerminal(
            InvitationStatus status,
            Instant at,
            String by,
            String invitedUserId
    ) {
        return new Invitation(
                id, orgId, identifier, role, status, preTuples, invitedBy,
                invitedUserId != null ? invitedUserId : this.invitedUserId,
                createdAt, expiresAt, at, by
        );
    }
}
