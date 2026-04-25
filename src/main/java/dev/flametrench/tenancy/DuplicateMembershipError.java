// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class DuplicateMembershipError extends TenancyError {
    public DuplicateMembershipError(String message) {
        super(message, "conflict.duplicate_membership");
    }
}
