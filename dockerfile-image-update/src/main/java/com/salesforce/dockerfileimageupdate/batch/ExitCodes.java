/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

/**
 * Process exit codes for a batch run, so callers can distinguish a quota-limited run from a
 * complete one.
 */
public final class ExitCodes {
    private ExitCodes() {
    }

    /** Every repository was considered; nothing was deferred for quota reasons. */
    public static final int COMPLETE = 0;
    /** The ignore manifest could not be loaded; no writes were performed. */
    public static final int INVALID_MANIFEST = 2;
    /** The quota ran out; some repositories were deferred to a later run. */
    public static final int QUOTA_EXHAUSTED = 3;
    /** The platform could not be read or the run otherwise failed. */
    public static final int RUN_FAILED = 4;
}
