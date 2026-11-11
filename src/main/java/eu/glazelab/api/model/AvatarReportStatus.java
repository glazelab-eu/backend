package eu.glazelab.api.model;

/**
 * A profile-picture report's state ({@code ck_avatar_report_status}): OPEN until a site admin dismisses it (the
 * picture may stay) or acts on it (the picture is removed and blocked from being uploaded again), or CLOSED without a
 * decision when the picture's account is deleted or banned (the picture and its evidence copy go with the account, so
 * nobody can decide it any more) or when nobody decided it within {@code glazelab.moderation.report-expiry} (its
 * evidence copy then goes too). Never reopened.
 */
public enum AvatarReportStatus { OPEN, DISMISSED, ACTIONED, CLOSED }
