// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

/** What an import wrote: items created, items updated, and items already as the import has them. */
public record ImportResult(int created, int updated, int unchanged) {
}
