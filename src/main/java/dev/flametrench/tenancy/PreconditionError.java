// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class PreconditionError extends TenancyError {
    private final String reason;

    public PreconditionError(String message, String reason) {
        super(message, "precondition." + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
