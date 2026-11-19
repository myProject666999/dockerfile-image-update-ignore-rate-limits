/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A selector for one dimension of an ignore rule.
 *
 * <p>A selector is either an exact name or a glob pattern. Globs support {@code *} (any run of
 * characters, including {@code /}) and {@code ?} (a single character); every other character is
 * literal. There is no partial or prefix matching for exact names: {@code acme/web} does not
 * match {@code acme/web-api}, and {@code nginx} does not match {@code nginx-ingress}. Use a
 * wildcard such as {@code acme/web*} or {@code nginx*} for that.</p>
 *
 * <h2>Repository selectors</h2>
 * <p>Repository names are always compared in {@code owner/name} form, case-insensitively. An
 * exact repository selector must therefore be written as {@code owner/name}; a bare name is
 * rejected at load time. A wildcard repository selector is matched against the full
 * {@code owner/name} string only, so {@code acme/*} covers every repository in the {@code acme}
 * organization.</p>
 *
 * <h2>Image selectors</h2>
 * <p>Exact image selectors are normalized with {@link ImageName#parse(String)} before they are
 * stored, so {@code nginx}, {@code library/nginx}, {@code openjdk} and
 * {@code index.docker.io/library/nginx} are all compared against the canonical image name.
 * Tags and digests are not allowed in an image selector. Wildcard image selectors are
 * lower-cased and matched against the canonical name, the namespace-relative path, and the
 * short name, so {@code nginx*} matches {@code docker.io/library/nginx-alpine}.</p>
 *
 * <p>Specificity is the number of literal characters in the selector. A wildcard always ranks
 * below an exact selector, and among selectors of the same kind more literal characters rank
 * higher.</p>
 */
public final class Selector {
    private final String text;
    private final boolean wildcard;
    private final Pattern pattern;

    private Selector(String text, boolean wildcard, Pattern pattern) {
        this.text = text;
        this.wildcard = wildcard;
        this.pattern = pattern;
    }

    /**
     * Build a repository selector.
     *
     * @param raw raw selector text
     * @return normalized repository selector
     * @throws IllegalArgumentException if the selector is empty or an exact selector is not
     *         written as {@code owner/name}
     */
    public static Selector forRepository(String raw) {
        if (isWildcardText(raw)) {
            return glob(raw.trim().toLowerCase(Locale.ROOT));
        }
        return exact(RepositoryName.parse(raw).canonical());
    }

    /**
     * Build an image selector.
     *
     * @param raw raw selector text
     * @return normalized image selector
     * @throws IllegalArgumentException if the selector is empty, contains a tag or digest, or
     *         an exact selector is malformed
     */
    public static Selector forImage(String raw) {
        if (isWildcardText(raw)) {
            return glob(normalizeImageWildcard(raw.trim()));
        }
        ImageName imageName = ImageName.parse(raw);
        if (imageName.tag().isPresent()) {
            throw new IllegalArgumentException(
                    "Image selector must not include a tag: " + raw);
        }
        if (raw.contains("@")) {
            throw new IllegalArgumentException(
                    "Image selector must not include a digest: " + raw);
        }
        return exact(imageName.canonical());
    }

    private static boolean isWildcardText(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Selector must not be empty");
        }
        return raw.indexOf('*') >= 0 || raw.indexOf('?') >= 0;
    }

    private static String normalizeImageWildcard(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        String[] aliases = {
                "index.docker.io/", "registry-1.docker.io/", "docker.io/"
        };
        for (String alias : aliases) {
            if (normalized.startsWith(alias)) {
                return ImageName.DEFAULT_REGISTRY + "/" + normalized.substring(alias.length());
            }
        }
        return normalized;
    }

    private static Selector exact(String normalizedText) {
        if (normalizedText.isEmpty()) {
            throw new IllegalArgumentException("Selector must not be empty");
        }
        return new Selector(normalizedText, false, null);
    }

    private static Selector glob(String normalizedGlob) {
        try {
            return new Selector(normalizedGlob, true, Pattern.compile(globToRegex(normalizedGlob)));
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Malformed selector pattern: " + normalizedGlob, e);
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    regex.append(".*");
                    break;
                case '?':
                    regex.append('.');
                    break;
                default:
                    regex.append(Pattern.quote(String.valueOf(c)));
                    break;
            }
        }
        return regex.toString();
    }

    /**
     * @param candidates candidate normalized forms
     * @return whether this selector matches any candidate in full
     */
    public boolean matches(List<String> candidates) {
        for (String candidate : candidates) {
            if (matchesText(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Test this selector against a single already-normalized form.
     *
     * @param candidate candidate form
     * @return whether this selector matches the candidate in full
     */
    public boolean matchesText(String candidate) {
        return wildcard ? pattern.matcher(candidate).matches() : text.equals(candidate);
    }

    /**
     * Conservative test for whether two selectors can both match the same value. Two exact
     * selectors overlap only when they are equal; an exact selector overlaps a wildcard only when
     * the wildcard covers it. Two wildcards overlap unless their literal prefixes are
     * incompatible: both patterns are anchored at the start of the value, so if neither fixed
     * prefix is a prefix of the other, no value can match both. This keeps genuinely disjoint
     * rules such as {@code acme/web*} and {@code acme/api*} from being reported as ambiguous,
     * while erring towards reporting ambiguity for the cases it cannot decide.
     *
     * @param other other selector
     * @return whether the two selectors may both match some value
     */
    public boolean possiblyOverlaps(Selector other) {
        if (other == null) {
            return true;
        }
        if (!wildcard && !other.wildcard) {
            return text.equals(other.text);
        }
        if (!wildcard) {
            return other.matchesText(text);
        }
        if (!other.wildcard) {
            return matchesText(other.text);
        }
        String leftPrefix = fixedPrefix();
        String rightPrefix = other.fixedPrefix();
        return leftPrefix.startsWith(rightPrefix) || rightPrefix.startsWith(leftPrefix);
    }

    /**
     * @return the literal text before the first wildcard character
     */
    private String fixedPrefix() {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '?') {
                return text.substring(0, i);
            }
        }
        return text;
    }

    /**
     * @return number of literal (non-wildcard) characters, used for specificity
     */
    public int literalLength() {
        int literal = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '*' && c != '?') {
                literal++;
            }
        }
        return literal;
    }

    public boolean isWildcard() {
        return wildcard;
    }

    public String text() {
        return text;
    }

    /**
     * Forms a repository selector is compared against. Repository selectors only ever see the
     * canonical {@code owner/name} form so that a rule for one organization cannot accidentally
     * match a same-named repository in another organization.
     *
     * @param repository repository being tested
     * @return single-element candidate list
     */
    public static List<String> repositoryCandidates(RepositoryName repository) {
        return Collections.singletonList(repository.canonical());
    }

    @Override
    public String toString() {
        return text;
    }
}
