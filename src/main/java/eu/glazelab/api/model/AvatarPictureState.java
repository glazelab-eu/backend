package eu.glazelab.api.model;

/**
 * Where a reported profile picture is now (backend guide "Profile pictures"): still the account's picture, or how it
 * went. The stored outcomes ({@code avatar_evidence.outcome}, {@code ck_avatar_evidence_outcome}) are all but CURRENT.
 */
public enum AvatarPictureState { CURRENT, REPLACED, OWNER_REMOVED, ADMIN_REMOVED, ACCOUNT_DELETED }
