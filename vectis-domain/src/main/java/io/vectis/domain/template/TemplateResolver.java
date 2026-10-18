package io.vectis.domain.template;

/**
 * Turns a template plus a delta into an effective configuration, by the rules of ADR-VEC-03.
 * Pure: no I/O, no clock, no randomness; neither input is modified.
 */
public final class TemplateResolver {

    /**
     * The version of the resolution rules. A resolved document stored with an older value is
     * recomputed, so bump it whenever a rule here changes what a template resolves to.
     */
    public static final int RESOLVER_VERSION = 1;

    private TemplateResolver() {
    }
}
