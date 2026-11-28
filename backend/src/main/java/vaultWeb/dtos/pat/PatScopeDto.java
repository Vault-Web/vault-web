package vaultWeb.dtos.pat;

import java.util.List;

/** A supported PAT scope and the core endpoints it unlocks. */
public record PatScopeDto(String value, String description, List<String> endpoints) {}
