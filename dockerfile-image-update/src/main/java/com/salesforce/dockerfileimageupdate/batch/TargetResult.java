/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.util.Optional;

/**
 * The outcome of considering one repository/image pair.
 *
 * <p>There are exactly five outcomes, matching the report:</p>
 * <ul>
 *     <li>{@link Status#UPDATED} - a write completed; counts against the quota,</li>
 *     <li>{@link Status#NOT_NEEDED} - already at the desired tag; does not count,</li>
 *     <li>{@link Status#IGNORED} - an ignore rule matched; does not count,</li>
 *     <li>{@link Status#DEFERRED} - the quota was exhausted; does not count,</li>
 *     <li>{@link Status#FAILED} - the write was attempted and failed; does not count.</li>
 * </ul>
 */
public final class TargetResult {
    /**
     * The five reportable outcomes.
     */
    public enum Status {
        UPDATED("updated"),
        NOT_NEEDED("no-op"),
        IGNORED("ignored"),
        DEFERRED("deferred"),
        FAILED("failed"),
        /**
         * Preview only: the repository would have been updated had this not been a preview.
         * It is reported as {@code would-update} and is not one of the five counted outcomes.
         */
        PREVIEW_UPDATE("would-update");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        /**
         * @return stable label used in reports
         */
        public String label() {
            return label;
        }
    }

    private final BatchTarget target;
    private final Status status;
    private final String ruleId;
    private final String reason;
    private final String quotaStatus;

    private TargetResult(BatchTarget target, Status status, String ruleId, String reason,
                         String quotaStatus) {
        this.target = target;
        this.status = status;
        this.ruleId = ruleId;
        this.reason = reason;
        this.quotaStatus = quotaStatus;
    }

    public static TargetResult updated(BatchTarget target) {
        return new TargetResult(target, Status.UPDATED, null, null, null);
    }

    public static TargetResult notNeeded(BatchTarget target) {
        return new TargetResult(target, Status.NOT_NEEDED, null, null, null);
    }

    public static TargetResult ignored(BatchTarget target, IgnoreRule rule) {
        return new TargetResult(target, Status.IGNORED, rule.id(),
                rule.reason().orElse(null), null);
    }

    public static TargetResult deferred(BatchTarget target, String quotaStatus) {
        return new TargetResult(target, Status.DEFERRED, null, null, quotaStatus);
    }

    static TargetResult previewUpdate(BatchTarget target) {
        return new TargetResult(target, Status.PREVIEW_UPDATE, null, null, null);
    }

    public static TargetResult failed(BatchTarget target, String reason) {
        return new TargetResult(target, Status.FAILED, null, reason, null);
    }

    public BatchTarget target() {
        return target;
    }

    public Status status() {
        return status;
    }

    /**
     * @return id of the matching rule, only for {@link Status#IGNORED}
     */
    public Optional<String> ruleId() {
        return Optional.ofNullable(ruleId);
    }

    /**
     * @return rule reason for ignored rows, failure cause for failed rows, otherwise empty
     */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    /**
     * @return quota description, only for {@link Status#DEFERRED}
     */
    public Optional<String> quotaStatus() {
        return Optional.ofNullable(quotaStatus);
    }

    /**
     * @return whether this outcome consumed quota
     */
    public boolean countsAgainstQuota() {
        return status == Status.UPDATED;
    }
}
