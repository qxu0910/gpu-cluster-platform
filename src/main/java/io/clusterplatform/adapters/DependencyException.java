package io.clusterplatform.adapters;

public class DependencyException extends RuntimeException {
    public final String code; public final boolean retryable;
    public DependencyException(String code,boolean retryable) { super(code); this.code=code; this.retryable=retryable; }
}
