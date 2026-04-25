// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class RoleHierarchyError extends TenancyError {
    public RoleHierarchyError(String message) {
        super(message, "forbidden.role_hierarchy");
    }
}
