package eu.glazelab.api.dto;

import java.util.UUID;

/** Answers shared by many endpoints. */
public final class CommonDtos {
    private CommonDtos() {}

    /** The ID of a created or joined record. */
    public record IdView(UUID id) {}
    /** The body of every error answer: an English message (the frontend translates the known ones). */
    public record ErrorView(String error) {}
    /** The answer of the health probe; build names the running build (the commit or label, else the project version). */
    public record HealthView(boolean ok, String build) {}
}
