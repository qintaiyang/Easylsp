package io.github.easylsp.demo;
/** Loaded with boot-parent class loaders to create independent native namespaces. */
public final class NamespaceProbe {
    public static int load(String directory, String backend) {
        System.load(directory + "/libdemo_namespace.so");
        return claim(directory, backend);
    }
    private static native int claim(String directory, String backend);
}
