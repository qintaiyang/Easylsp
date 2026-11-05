package io.github.easylsp.api;
import java.lang.reflect.Member;
public interface HookDriver {
    enum Mode { BEFORE, AFTER, REPLACE }
    HookHandle install(Member member, Mode mode, HookCallback callback) throws Throwable;
}
