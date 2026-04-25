// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class NotFoundError extends TenancyError {
    public NotFoundError(String message) {
        super(message, "not_found");
    }
}
