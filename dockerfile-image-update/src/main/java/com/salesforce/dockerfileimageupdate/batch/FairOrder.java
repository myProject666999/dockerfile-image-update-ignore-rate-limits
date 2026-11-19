/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Orders targets so that repeated quota-limited runs eventually cover everything instead of
 * always starting with the alphabetically first repositories.
 *
 * <p>Targets are sorted by when they were last successfully updated, oldest first; targets that
 * have never been updated sort before every target that has. Remaining ties are broken by
 * canonical repository then image name so that a run is deterministic.</p>
 *
 * <p>The ordering state lives in the run-directory state file. If that file is lost, the
 * ordering falls back to alphabetical; this can repeat work but cannot skip a target.</p>
 */
public final class FairOrder implements Comparator<BatchTarget> {
    private final Map<String, Instant> lastSuccessByKey;

    /**
     * @param lastSuccessByKey last-success timestamps keyed by {@link BatchTarget#key()}
     */
    public FairOrder(Map<String, Instant> lastSuccessByKey) {
        this.lastSuccessByKey = lastSuccessByKey;
    }

    @Override
    public int compare(BatchTarget a, BatchTarget b) {
        Instant left = lastSuccessByKey.get(a.key());
        Instant right = lastSuccessByKey.get(b.key());
        if (left == null && right != null) {
            return -1;
        }
        if (left != null && right == null) {
            return 1;
        }
        if (left != null) {
            int byTime = left.compareTo(right);
            if (byTime != 0) {
                return byTime;
            }
        }
        return a.compareTo(b);
    }

    /**
     * @param targets targets to order
     * @return new ordered list
     */
    public List<BatchTarget> sort(List<BatchTarget> targets) {
        List<BatchTarget> copy = new java.util.ArrayList<>(targets);
        copy.sort(this);
        return copy;
    }
}
