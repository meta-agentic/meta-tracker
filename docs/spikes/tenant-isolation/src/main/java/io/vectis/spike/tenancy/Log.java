package io.vectis.spike.tenancy;

/** Results go to stdout, one line each, so a run can be captured and quoted as it is. */
final class Log {

    private Log() {}

    static void out(String format, Object... args) {
        System.out.println(String.format(format, args));
        System.out.flush();
    }
}
