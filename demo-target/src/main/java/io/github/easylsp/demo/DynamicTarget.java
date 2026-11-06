package io.github.easylsp.demo;
/** Loaded again in independent ClassLoaders by the opt-in JNI fixture. */
public final class DynamicTarget {
    public static native int value(int value);
    public static native int value(int left, int right);
}
