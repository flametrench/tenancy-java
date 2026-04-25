// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class InvitationNotPendingError extends TenancyError {
    public InvitationNotPendingError(String message) {
        super(message, "conflict.invitation_not_pending");
    }
}
