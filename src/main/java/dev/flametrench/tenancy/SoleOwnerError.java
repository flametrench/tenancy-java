// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class SoleOwnerError extends TenancyError {
    public SoleOwnerError(String message) {
        super(message, "forbidden.sole_owner");
    }
}
