// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class ForbiddenError extends TenancyError {
    public ForbiddenError(String message) {
        super(message, "forbidden");
    }
}
