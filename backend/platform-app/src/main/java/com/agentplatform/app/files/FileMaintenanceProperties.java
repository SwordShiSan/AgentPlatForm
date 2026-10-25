package com.agentplatform.app.files;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Operational admission only; accepted commands and their receipts remain authoritative. */
@ConfigurationProperties("platform.workspace.files.maintenance")
public record FileMaintenanceProperties(@DefaultValue("true") boolean scanAdmissionEnabled) {
    boolean scanAdmission(boolean maintenanceProfile) {
        if (!scanAdmissionEnabled && !maintenanceProfile) {
            throw new IllegalArgumentException("WORKSPACE_MAINTENANCE_PROFILE_REQUIRED");
        }
        return scanAdmissionEnabled;
    }
}
