package io.github.easylsp.api;
import java.lang.reflect.Member;
public final class HookParam {
    public final Member member;
    public final Object thisObject;
    public final Object[] args;
    private Object result;
    private Throwable throwable;
    private boolean changed;
    public HookParam(Member member, Object thisObject, Object[] args, Object result, Throwable throwable) {
        this.member = member; this.thisObject = thisObject; this.args = args;
        this.result = result; this.throwable = throwable;
    }
    public Object getResult() { return result; }
    public Throwable getThrowable() { return throwable; }
    public boolean hasChangedOutcome() { return changed; }
    public void setResult(Object value) { result = value; throwable = null; changed = true; }
    public void skipWith(Object value) { setResult(value); }
    public void setThrowable(Throwable value) { throwable = value; result = null; changed = true; }
}
