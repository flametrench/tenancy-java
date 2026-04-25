// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

/** Base class for every tenancy-layer error. */
public class TenancyError extends RuntimeException {
    private final String code;

    public TenancyError(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
