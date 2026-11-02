package com.transport.autopartage.vehiculeRoute;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {VehiculeController.class, CarReadController.class})
class VehiculeExceptionHandler {
    @ExceptionHandler(VehiculeException.DuplicateRegistration.class)
    ResponseEntity<ErrorResponse> duplicateRegistration(VehiculeException.DuplicateRegistration exception) {
        return error(HttpStatus.CONFLICT, "DUPLICATE_REGISTRATION", exception.getMessage());
    }

    @ExceptionHandler(VehiculeException.UnknownCity.class)
    ResponseEntity<ErrorResponse> unknownCity(VehiculeException.UnknownCity exception) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_CITY", exception.getMessage());
    }

    @ExceptionHandler(VehiculeException.UnknownCategory.class)
    ResponseEntity<ErrorResponse> unknownCategory(VehiculeException.UnknownCategory exception) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_CATEGORY", exception.getMessage());
    }

    @ExceptionHandler(VehiculeException.NotFound.class)
    ResponseEntity<ErrorResponse> notFound(VehiculeException.NotFound exception) {
        return error(HttpStatus.NOT_FOUND, "VEHICULE_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(VehiculeException.OwnershipRequired.class)
    ResponseEntity<ErrorResponse> ownershipRequired(VehiculeException.OwnershipRequired exception) {
        return error(HttpStatus.FORBIDDEN, "OWNERSHIP_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(VehiculeException.InvalidCaller.class)
    ResponseEntity<ErrorResponse> invalidCaller(VehiculeException.InvalidCaller exception) {
        return error(HttpStatus.UNAUTHORIZED, "INVALID_CALLER", exception.getMessage());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ErrorResponse> businessError(org.springframework.web.server.ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(new ErrorResponse(
                exception.getStatusCode().value(), exception.getReason(), exception.getReason(), Map.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> malformedBody() {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_BODY", "The request body is missing or invalid.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> invalidParameter() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "A request parameter has an invalid value.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validationFailed(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(field -> fields.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(400, "VALIDATION_FAILED", "Request validation failed.", fields));
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), code, message, Map.of()));
    }

    record ErrorResponse(int status, String code, String message, Map<String, String> fieldErrors) {}
}
