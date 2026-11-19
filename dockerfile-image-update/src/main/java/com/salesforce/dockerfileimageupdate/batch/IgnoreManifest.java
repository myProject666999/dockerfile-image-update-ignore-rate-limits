/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The ignore manifest: a validated, ordered set of {@link IgnoreRule}s.
 *
 * <h2>Format</h2>
 * <pre>{@code
 * {
 *   "rules": [
 *     {
 *       "id": "freeze-billing-image",
 *       "action": "ignore",
 *       "repository": "acme/billing",
 *       "image": "quay.io/acme/base",
 *       "reason": "billing team pinned this base image",
 *       "expiresOn": "2026-12-31"
 *     },
 *     {
 *       "id": "allow-web-in-cache",
 *       "action": "include",
 *       "repository": "acme/cache",
 *       "image": "docker.io/library/nginx"
 *     }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code repository} and {@code image} are both optional, but a rule must set at least one
 * of them. {@code action} defaults to {@code ignore}. {@code expiresOn} is an ISO-8601 local
 * date on which the rule stops applying: it is ignored through the day before {@code expiresOn}
 * (inclusive) and no longer matches from {@code expiresOn} onward.
 * Unknown fields are rejected so that a typo cannot silently disable a protection.</p>
 *
 * <h2>Resolution</h2>
 * <p>Every active rule that matches a repository/image pair is a candidate. The most specific
 * candidate wins (see {@link IgnoreRule}); ties are resolved in favour of {@code ignore}, so a
 * mistake never exposes a repository that was meant to be protected. Conflicts between rules
 * whose specificity is crossed and whose actions disagree are rejected when the manifest is
 * loaded, as are rules with duplicate ids or duplicate scopes.</p>
 */
public final class IgnoreManifest {
    private static final Set<String> RULE_FIELDS;

    static {
        Set<String> fields = new HashSet<>();
        fields.add("id");
        fields.add("action");
        fields.add("repository");
        fields.add("image");
        fields.add("reason");
        fields.add("expiresOn");
        RULE_FIELDS = Collections.unmodifiableSet(fields);
    }

    private final List<IgnoreRule> rules;

    IgnoreManifest(List<IgnoreRule> rules) throws ManifestException {
        List<IgnoreRule> copy = new ArrayList<>(rules);
        copy.sort(IgnoreRule.BY_ID);
        this.rules = Collections.unmodifiableList(copy);
        validateRules(this.rules);
    }

    /**
     * @return empty manifest that ignores nothing
     */
    public static IgnoreManifest empty() {
        try {
            return new IgnoreManifest(Collections.emptyList());
        } catch (ManifestException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Parse and validate a manifest.
     *
     * @param json manifest JSON text
     * @return validated manifest
     * @throws ManifestException if the JSON is malformed or any rule is invalid, ambiguous or contradictory
     */
    public static IgnoreManifest parse(String json) throws ManifestException {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new ManifestException("Ignore manifest is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new ManifestException("Ignore manifest must be a JSON object with a 'rules' array");
        }
        JsonObject object = root.getAsJsonObject();
        for (String field : object.keySet()) {
            if (!"rules".equals(field)) {
                throw new ManifestException("Unknown top-level field '" + field + "' in ignore manifest");
            }
        }
        JsonElement rulesElement = object.get("rules");
        if (rulesElement == null || !rulesElement.isJsonArray()) {
            throw new ManifestException("Ignore manifest must contain a 'rules' array");
        }
        JsonArray array = rulesElement.getAsJsonArray();
        List<IgnoreRule> parsed = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            parsed.add(parseRule(array.get(i), i));
        }
        return new IgnoreManifest(parsed);
    }    /**
     * Parse and validate a manifest from a reader.
     *
     * @param reader reader over the manifest JSON
     * @return validated manifest
     * @throws ManifestException if the JSON is malformed or any rule is invalid
     * @throws IOException if the reader fails
     */
    public static IgnoreManifest parse(Reader reader) throws ManifestException, IOException {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[4096];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            builder.append(buffer, 0, read);
        }
        return parse(builder.toString());
    }

    private static IgnoreRule parseRule(JsonElement element, int index) throws ManifestException {
        String position = "rules[" + index + "]";
        if (element == null || !element.isJsonObject()) {
            throw new ManifestException(position + " must be a JSON object");
        }
        JsonObject object = element.getAsJsonObject();
        for (String field : object.keySet()) {
            if (!RULE_FIELDS.contains(field)) {
                throw new ManifestException(
                        position + " has unknown field '" + field + "'");
            }
        }

        String id = requiredString(object, "id", position);
        IgnoreRule.Action action = IgnoreRule.Action.IGNORE;
        if (object.has("action")) {
            try {
                action = IgnoreRule.Action.parse(optionalString(object, "action", position));
            } catch (IllegalArgumentException e) {
                throw new ManifestException(position + ": " + e.getMessage(), e);
            }
        }

        Selector repositorySelector = null;
        if (object.has("repository")) {
            try {
                repositorySelector = Selector.forRepository(requiredString(object, "repository", position));
            } catch (IllegalArgumentException e) {
                throw new ManifestException(
                        position + " (" + id + "): invalid repository selector: " + e.getMessage(), e);
            }
        }

        Selector imageSelector = null;
        if (object.has("image")) {
            try {
                imageSelector = Selector.forImage(requiredString(object, "image", position));
            } catch (IllegalArgumentException e) {
                throw new ManifestException(
                        position + " (" + id + "): invalid image selector: " + e.getMessage(), e);
            }
        }

        if (repositorySelector == null && imageSelector == null) {
            throw new ManifestException(
                    position + " (" + id + "): a rule must set 'repository', 'image', or both");
        }

        String reason = object.has("reason") ? optionalString(object, "reason", position) : null;

        LocalDate expiresOn = null;
        if (object.has("expiresOn") && !object.get("expiresOn").isJsonNull()) {
            String value = optionalString(object, "expiresOn", position);
            try {
                expiresOn = LocalDate.parse(value.trim());
            } catch (DateTimeParseException e) {
                throw new ManifestException(
                        position + " (" + id + "): 'expiresOn' must be an ISO-8601 date such as 2026-12-31, was '"
                                + value + "'", e);
            }
        }

        try {
            return new IgnoreRule(id, action, repositorySelector, imageSelector, reason, expiresOn);
        } catch (IllegalArgumentException e) {
            throw new ManifestException(position + ": " + e.getMessage(), e);
        }
    }

    private static String requiredString(JsonObject object, String field, String position)
            throws ManifestException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ManifestException(position + ": '" + field + "' must be a string");
        }
        String text = value.getAsString();
        if (text.trim().isEmpty()) {
            throw new ManifestException(position + ": '" + field + "' must not be empty");
        }
        return text;
    }

    private static String optionalString(JsonObject object, String field, String position)
            throws ManifestException {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ManifestException(position + ": '" + field + "' must be a string");
        }
        return value.getAsString();
    }

    private static void validateRules(List<IgnoreRule> rules) throws ManifestException {
        Set<String> ids = new HashSet<>();
        for (IgnoreRule rule : rules) {
            if (!ids.add(rule.id())) {
                throw new ManifestException("Duplicate rule id '" + rule.id() + "'");
            }
        }
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                checkPair(rules.get(i), rules.get(j));
            }
        }
    }

    private static void checkPair(IgnoreRule a, IgnoreRule b) throws ManifestException {
        if (a.sameScopeAs(b)) {
            if (a.action() == b.action()) {
                throw new ManifestException("Rules '" + a.id() + "' and '" + b.id()
                        + "' both " + describe(a.action()) + " the same scope; merge them into one rule");
            }
            throw new ManifestException("Rules '" + a.id() + "' and '" + b.id()
                    + "' describe the same scope with opposite actions; this is contradictory");
        }
        if (a.action() == b.action() || !a.scopeOverlaps(b)) {
            return;
        }
        // The actions disagree and the rules can match the same pair. That is only acceptable
        // when one rule is more specific than the other componentwise; otherwise the outcome
        // depends on an ordering nobody can predict.
        if (!a.strictlyMoreSpecificThan(b) && !b.strictlyMoreSpecificThan(a)) {
            throw new ManifestException("Rules '" + a.id() + "' and '" + b.id()
                    + "' overlap but neither is more specific than the other in every dimension,"
                    + " and their actions disagree; this is ambiguous. Constrain each rule to the"
                    + " repositories or images it is actually meant for.");
        }
    }

    private static String describe(IgnoreRule.Action action) {
        return action == IgnoreRule.Action.IGNORE ? "ignore" : "include";
    }

    /**
     * Resolve which rule, if any, applies to a repository/image pair.
     *
     * @param repository repository under test
     * @param image image under test
     * @param today current local date from the injected clock
     * @return the winning active rule, if any
     */
    public Optional<IgnoreRule> decide(RepositoryName repository, ImageName image, LocalDate today) {
        List<IgnoreRule> active = new ArrayList<>();
        for (IgnoreRule rule : rules) {
            if (rule.isActive(today) && rule.matches(repository, image)) {
                active.add(rule);
            }
        }
        if (active.isEmpty()) {
            return Optional.empty();
        }
        // Validated manifests never contain two overlapping rules with opposite actions that
        // are not strictly ordered, so at most one active rule is the unique maximum. Iterate
        // anyway to keep the method total if a manifest is constructed without validation.
        IgnoreRule winner = active.get(0);
        for (IgnoreRule candidate : active) {
            if (candidate.strictlyMoreSpecificThan(winner)) {
                winner = candidate;
            } else if (!winner.strictlyMoreSpecificThan(candidate)
                    && preferredOnTie(candidate, winner) == candidate) {
                winner = candidate;
            }
        }
        return Optional.of(winner);
    }

    private static IgnoreRule preferredOnTie(IgnoreRule candidate, IgnoreRule current) {
        if (candidate.action() != current.action()) {
            return candidate.action() == IgnoreRule.Action.IGNORE ? candidate : current;
        }
        return candidate.id().compareTo(current.id()) < 0 ? candidate : current;
    }

    /**
     * @param today current local date from the injected clock
     * @param warningWindowDays how many days before expiry to warn
     * @return active rules that expire within the warning window, in id order
     */
    public List<IgnoreRule> expiringSoon(LocalDate today, int warningWindowDays) {
        List<IgnoreRule> expiring = new ArrayList<>();
        for (IgnoreRule rule : rules) {
            if (rule.expiresSoon(today, warningWindowDays)) {
                expiring.add(rule);
            }
        }
        return expiring;
    }

    /**
     * @param today current local date from the injected clock
     * @return rules that have expired and no longer apply, in id order
     */
    public List<IgnoreRule> expired(LocalDate today) {
        List<IgnoreRule> expired = new ArrayList<>();
        for (IgnoreRule rule : rules) {
            if (!rule.isActive(today)) {
                expired.add(rule);
            }
        }
        return expired;
    }

    public List<IgnoreRule> rules() {
        return rules;
    }

    public int size() {
        return rules.size();
    }

    /**
     * Thrown when a manifest cannot be loaded or is rejected as ambiguous.
     */
    public static final class ManifestException extends Exception {
        public ManifestException(String message) {
            super(message);
        }

        public ManifestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
