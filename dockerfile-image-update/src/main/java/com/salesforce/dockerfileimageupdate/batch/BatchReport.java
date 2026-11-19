/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The fixed-format report for one batch run, intended to be parsed by external scripts.
 *
 * <h2>Layout</h2>
 * <pre>
 * # dfiu-batch-report runner=&lt;name&gt; started=&lt;iso-instant&gt; maxRepositories=&lt;n|unlimited&gt; state=&lt;loaded|reset-missing|reset-corrupt&gt;
 * TARGET &lt;repository&gt; &lt;image&gt; current=&lt;tag|-> desired=&lt;tag&gt; status=&lt;updated|no-op|ignored|deferred|failed&gt; rule=&lt;id&gt; reason=&lt;text&gt; quota=&lt;text&gt;
 * ...
 * # ignored-detail ...
 * SUMMARY updated=&lt;n&gt; no-op=&lt;n&gt; ignored=&lt;n&gt; deferred=&lt;n&gt; failed=&lt;n&gt; total=&lt;n&gt;
 * </pre>
 *
 * <p>Every row starts with {@code TARGET} and carries all fields positionally in a fixed order;
 * fields that do not apply are emitted as {@code -} or as {@code key=-}. Free-text values have
 * newlines and tabs replaced by spaces so that one target is always exactly one line.</p>
 */
public final class BatchReport {
    private static final String FIELD_SEPARATOR = " ";

    private final String runnerName;
    private final String startedAt;
    private final String maxRepositories;
    private final String stateStatus;
    private final List<TargetResult> results;
    private final List<IgnoreRule> expiringRules;
    private final int expiryWarningDays;
    private final boolean preview;

    public BatchReport(String runnerName, String startedAt, String maxRepositories,
                       String stateStatus, List<TargetResult> results,
                       List<IgnoreRule> expiringRules, int expiryWarningDays, boolean preview) {
        this.runnerName = runnerName;
        this.startedAt = startedAt;
        this.maxRepositories = maxRepositories;
        this.stateStatus = stateStatus;
        this.results = results;
        this.expiringRules = expiringRules;
        this.expiryWarningDays = expiryWarningDays;
        this.preview = preview;
    }

    /**
     * Render the report.
     *
     * @return multi-line report text, newline-terminated
     */
    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("# dfiu-batch-report")
                .append(" runner=").append(clean(runnerName))
                .append(" started=").append(clean(startedAt))
                .append(" maxRepositories=").append(clean(maxRepositories))
                .append(" state=").append(clean(stateStatus))
                .append(" mode=").append(preview ? "preview" : "apply")
                .append('\n');

        for (IgnoreRule rule : expiringRules) {
            builder.append("# expiring-rule id=").append(clean(rule.id()))
                    .append(" expiresOn=").append(clean(rule.expiresOn()
                            .map(Object::toString).orElse("-")))
                    .append(" warningWindowDays=").append(expiryWarningDays)
                    .append('\n');
        }

        for (TargetResult result : results) {
            builder.append("TARGET ")
                    .append(clean(result.target().repository().canonical())).append(FIELD_SEPARATOR)
                    .append(clean(result.target().image().canonical())).append(FIELD_SEPARATOR)
                    .append("current=").append(clean(result.target().currentTag().orElse("-"))).append(FIELD_SEPARATOR)
                    .append("desired=").append(clean(result.target().desiredTag().orElse("-"))).append(FIELD_SEPARATOR)
                    .append("status=").append(result.status().label()).append(FIELD_SEPARATOR)
                    .append("rule=").append(clean(result.ruleId().orElse("-"))).append(FIELD_SEPARATOR)
                    .append("reason=").append(clean(result.reason().orElse("-"))).append(FIELD_SEPARATOR)
                    .append("quota=").append(clean(result.quotaStatus().orElse("-")))
                    .append('\n');
        }

        Map<TargetResult.Status, Integer> counts = new EnumMap<>(TargetResult.Status.class);
        for (TargetResult.Status status : TargetResult.Status.values()) {
            counts.put(status, 0);
        }
        for (TargetResult result : results) {
            counts.put(result.status(), counts.get(result.status()) + 1);
        }
        builder.append("SUMMARY")
                .append(" updated=").append(counts.get(TargetResult.Status.UPDATED))
                .append(" no-op=").append(counts.get(TargetResult.Status.NOT_NEEDED))
                .append(" ignored=").append(counts.get(TargetResult.Status.IGNORED))
                .append(" deferred=").append(counts.get(TargetResult.Status.DEFERRED))
                .append(" failed=").append(counts.get(TargetResult.Status.FAILED))
                .append(" total=").append(results.size())
                .append('\n');
        if (counts.get(TargetResult.Status.PREVIEW_UPDATE) > 0) {
            // Preview is an extra mode; keep the five-outcome summary above stable and report
            // the would-be writes on their own line.
            builder.append("PREVIEW would-update=")
                    .append(counts.get(TargetResult.Status.PREVIEW_UPDATE))
                    .append('\n');
        }
        return builder.toString();
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }

    /**
     * @return whether any target was deferred because the quota ran out
     */
    public boolean quotaExhausted() {
        return results.stream().anyMatch(r -> r.status() == TargetResult.Status.DEFERRED);
    }

    public List<TargetResult> results() {
        return results;
    }

    /**
     * @return the state status token used in the header
     */
    public static String describeState(StateStore.LoadStatus status) {
        switch (status) {
            case LOADED:
                return "loaded";
            case CORRUPT:
                return "reset-corrupt";
            default:
                return "reset-missing";
        }
    }

}
