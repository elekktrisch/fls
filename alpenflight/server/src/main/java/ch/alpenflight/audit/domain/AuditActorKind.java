package ch.alpenflight.audit.domain;

public enum AuditActorKind {
    NORMAL,
    ANONYMOUS_PUBLIC,
    SYSTEM,
    LEGACY_MIGRATED
}
