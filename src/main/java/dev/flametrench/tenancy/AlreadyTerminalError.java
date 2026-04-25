// Copyright 2026 NDC Digital, LLC
// SPDX-License-Identifier: Apache-2.0

package dev.flametrench.tenancy;

public class AlreadyTerminalError extends TenancyError {
    public AlreadyTerminalError(String message) {
        super(message, "already_terminal");
    }
}
