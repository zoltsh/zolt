package sh.zolt.sbom;

import java.util.Optional;
import sh.zolt.project.LicensePolicyException;

/** Whether one configured exception matched and was actually required by the resolved closure. */
public record LicenseExceptionAudit(
        LicensePolicyException exception,
        LicenseExceptionAuditStatus status,
        Optional<String> member,
        Optional<String> resolvedVersion) {
    public LicenseExceptionAudit(
            LicensePolicyException exception,
            LicenseExceptionAuditStatus status,
            Optional<String> resolvedVersion) {
        this(exception, status, Optional.empty(), resolvedVersion);
    }

    public LicenseExceptionAudit {
        member = member == null ? Optional.empty() : member;
        resolvedVersion = resolvedVersion == null ? Optional.empty() : resolvedVersion;
    }

    public LicenseExceptionAudit ownedBy(Optional<String> owner) {
        return new LicenseExceptionAudit(exception, status, owner, resolvedVersion);
    }

    public boolean failure() {
        return status != LicenseExceptionAuditStatus.USED;
    }
}
