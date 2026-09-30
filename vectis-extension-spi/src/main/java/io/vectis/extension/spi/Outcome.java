// SPDX-License-Identifier: Apache-2.0
package io.vectis.extension.spi;

/**
 * How an item in {@link StatusCategory#END_STATE} ended. Only an ended item has one.
 */
public enum Outcome {

    /** The work was completed and delivered. */
    DELIVERED,

    /**
     * The work stopped without completion and has no viable path forward. It is neither
     * not-started nor done: the item is closed, but nothing was delivered.
     */
    DISCONTINUED
}
