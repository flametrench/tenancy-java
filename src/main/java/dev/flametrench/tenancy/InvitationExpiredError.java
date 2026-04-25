// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class InvitationExpiredError extends TenancyError {
    public InvitationExpiredError(String message) {
        super(message, "conflict.invitation_expired");
    }
}
