package io.clusterplatform.domain;

public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public ApiException(int status, String code) { super(code); this.status=status; this.code=code; }
    public static ApiException conflict(String code) { return new ApiException(409,code); }
}
