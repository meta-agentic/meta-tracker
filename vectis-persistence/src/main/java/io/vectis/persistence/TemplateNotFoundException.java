// SPDX-License-Identifier: Apache-2.0
package io.vectis.persistence;

/**
 * Thrown when a template reference names no version the caller can see: an unknown key or
 * version, or another tenant's template, which is reported the same way so its existence does
 * not leak.
 */
public class TemplateNotFoundException extends RuntimeException {

    private final TemplateRef ref;

    public TemplateNotFoundException(TemplateRef ref) {
        super("template " + ref + " does not exist");
        this.ref = ref;
    }

    public TemplateRef ref() {
        return ref;
    }
}
