package io.clusterplatform.api;

import io.clusterplatform.domain.ApiException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler(ApiException.class) ResponseEntity<?> known(ApiException x) { return response(x.status,x.code); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class,
        org.springframework.web.bind.MissingRequestHeaderException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception x) { return response(400,"invalid_request"); }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> database(Exception x) { return response(503,"database_unavailable"); }
    @ExceptionHandler(Exception.class) ResponseEntity<?> unexpected(Exception x) { return response(500,"internal_error"); }
    private ResponseEntity<?> response(int status,String code) { return ResponseEntity.status(status).body(Map.of("error",Map.of("code",code,"message",code))); }
}
