/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A single entry in the ignore manifest.
 *
 * <p>A rule can constrain the repository dimension, the image dimension, or both. A rule that
 * names neither is rejected at load time. The {@link Action} says what happens when the rule is
 * the most specific match:</p>
 * <ul>
 *     <li>{@link Action#IGNORE} - the repository is skipped,</li>
 *     <li>{@link Action#INCLUDE} - explicit allow, which overrides a broader ignore.</li>
 * </ul>
 *
 * <p>Specificity is a tuple compared dimension by dimension, repository first:</p>
 * <ol>
 *     <li>is the dimension constrained at all (unconstrained ranks lowest),</li>
 *     <li>is the selector exact rather than a wildcard (exact ranks higher),</li>
 *     <li>how many literal characters the selector has (more is more specific).</li>
 * </ol>
 * A rule <em>dominates</em> another when every component is greater-or-equal and at least one
 * is strictly greater. Rules whose tuples are crossed (one more specific in the repository
 * dimension, the other in the image dimension) cannot be ordered; see
 * {@link IgnoreManifest} for how that is rejected when the actions disagree.
 */
public final class IgnoreRule {
    /**
     * What to do when this rule is the most specific match.
     */
    public enum Action {
        IGNORE,
        INCLUDE;

        static Action parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("Rule action must not be null");
            }
            switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "ignore":
                    return IGNORE;
                case "include":
                    return INCLUDE;
                default:
                    throw new IllegalArgumentException(
                            "Unknown rule action '" + value + "' (expected ignore or include)");
            }
        }
    }

    private final String id;
    private final Action action;
    private final Selector repositorySelector;
    private final Selector imageSelector;
    private final String reason;
    private final LocalDate expiresOn;

    /**
     * @param id unique rule id used in reports
     * @param action action to take on match
     * @param repositorySelector repository constraint, may be {@code null} for any
     * @param imageSelector image constraint, may be {@code null} for any
     * @param reason human-readable reason
     * @param expiresOn first day on which the rule no longer applies, {@code null} for no expiry
     */
    public IgnoreRule(String id, Action action, Selector repositorySelector,
                      Selector imageSelector, String reason, LocalDate expiresOn) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("Rule id must not be empty");
        }
        if (repositorySelector == null && imageSelector == null) {
            throw new IllegalArgumentException(
                    "Rule " + id + " must constrain a repository, an image, or both");
        }
        this.id = id.trim();
        this.action = Objects.requireNonNull(action, "action");
        this.repositorySelector = repositorySelector;
        this.imageSelector = imageSelector;
        this.reason = reason;
        this.expiresOn = expiresOn;
    }

    public String id() {
        return id;
    }

    public Action action() {
        return action;
    }

    public Optional<Selector> repositorySelector() {
        return Optional.ofNullable(repositorySelector);
    }

    public Optional<Selector> imageSelector() {
        return Optional.ofNullable(imageSelector);
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    public Optional<LocalDate> expiresOn() {
        return Optional.ofNullable(expiresOn);
    }

    /**
     * @param today current local date from the injected clock
     * @return whether the rule still applies on {@code today}
     */
    public boolean isActive(LocalDate today) {
        return expiresOn == null || today.isBefore(expiresOn);
    }

    /**
     * @param today current local date from the injected clock
     * @param warningWindowDays how many days before expiry to warn
     * @return whether the rule is active but expires within the warning window
     */
    public boolean expiresSoon(LocalDate today, int warningWindowDays) {
        if (expiresOn == null || !isActive(today)) {
            return false;
        }
        return !today.plusDays(warningWindowDays).isBefore(expiresOn);
    }

    /**
     * Whether this rule applies to the given repository and image. Expiry is handled by the
     * caller so that the same parsed manifest can be evaluated against different clock values.
     *
     * @param repository repository under test
     * @param image image under test
     * @return whether both constrained dimensions match
     */
    public boolean matches(RepositoryName repository, ImageName image) {
        if (repositorySelector != null
                && !repositorySelector.matches(Selector.repositoryCandidates(repository))) {
            return false;
        }
        if (imageSelector == null) {
            return true;
        }
        List<String> forms = new ArrayList<>();
        forms.add(image.canonical());
        forms.add(image.repositoryPath());
        forms.add(image.shortName());
        return imageSelector.matches(forms);
    }

    /**
     * Componentwise dominance: this rule is at least as specific in every dimension. This is the
     * property that makes two rules orderable. Each dimension is compared on its own triple
     * (constrained, exact, literal length) in that order, so an exact selector beats a wildcard
     * in the same dimension regardless of how many literal characters the wildcard has.
     *
     * <p>A rule constrained only on the repository does not dominate a rule constrained only on
     * the image, because the two say nothing about each other's dimension and their precedence
     * would be unpredictable.</p>
     *
     * @param other other rule
     * @return this rule's specificity is greater-or-equal in every dimension
     */
    public boolean atLeastAsSpecificAs(IgnoreRule other) {
        return compareDimension(repositorySelector, other.repositorySelector) >= 0
                && compareDimension(imageSelector, other.imageSelector) >= 0;
    }

    /**
     * @param other other rule
     * @return whether this rule is strictly more specific than {@code other} in every dimension,
     *         with at least one dimension strictly greater
     */
    public boolean strictlyMoreSpecificThan(IgnoreRule other) {
        if (!atLeastAsSpecificAs(other)) {
            return false;
        }
        return compareDimension(repositorySelector, other.repositorySelector) > 0
                || compareDimension(imageSelector, other.imageSelector) > 0;
    }

    private static int compareDimension(Selector a, Selector b) {
        return Integer.compare(dimensionRank(a), dimensionRank(b));
    }

    private static int dimensionRank(Selector selector) {
        if (selector == null) {
            return -1;
        }
        // Exact selectors outrank wildcards; among exact selectors the longer text is more
        // specific; among wildcards the one with more literal characters is more specific.
        int exactness = selector.isWildcard() ? 0 : 1_000_000;
        return exactness + selector.literalLength();
    }

    /**
     * Whether the two rules' selectors could both apply to the same repository/image pair.
     * Two rules that never apply to the same pair cannot contradict one another, regardless of
     * their relative specificity (for example an ignore for {@code acme/web} and an include for
     * {@code acme/api}).
     *
     * @param other other rule
     * @return whether the rules overlap
     */
    public boolean scopeOverlaps(IgnoreRule other) {
        return selectorOverlaps(repositorySelector, other.repositorySelector)
                && selectorOverlaps(imageSelector, other.imageSelector);
    }

    private static boolean selectorOverlaps(Selector a, Selector b) {
        if (a == null || b == null) {
            return true;
        }
        return a.possiblyOverlaps(b);
    }

    /**
     * A stable order used only to make reporting deterministic when two rules tie.
     */
    public static final Comparator<IgnoreRule> BY_ID = Comparator.comparing(IgnoreRule::id);

    /**
     * @return whether every selector text of the two rules is identical
     */
    public boolean sameScopeAs(IgnoreRule other) {
        return selectorTextEquals(repositorySelector, other.repositorySelector)
                && selectorTextEquals(imageSelector, other.imageSelector);
    }

    private static boolean selectorTextEquals(Selector a, Selector b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.text().equalsIgnoreCase(b.text());
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(id).append('[').append(action);
        repositorySelector().ifPresent(s -> builder.append(" repo=").append(s.text()));
        imageSelector().ifPresent(s -> builder.append(" image=").append(s.text()));
        expiresOn().ifPresent(d -> builder.append(" expires=").append(d));
        return builder.append(']').toString();
    }
}
