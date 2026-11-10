package com.transport.bff;

import com.transport.mobility.contracts.v1.QuoteContracts.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/quotes")
class QuoteController {
    private final PartnerClient partners;
    private final SelectionService selections;

    QuoteController(PartnerClient partners, SelectionService selections) {
        this.partners = partners;
        this.selections = selections;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Quote issue(@RequestBody Selection selection, @AuthenticationPrincipal Jwt principal) {
        String token = technicalToken();
        return partners.issue(new Issue(subject(principal), selections.resolve(selection, token)), token);
    }

    @GetMapping("/{id}")
    Quote get(@PathVariable UUID id, @AuthenticationPrincipal Jwt principal) {
        return partners.getQuote(id, subject(principal), technicalToken());
    }

    private static String subject(Jwt principal) {
        String subject = principal.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_SUBJECT");
        }
        return subject;
    }

    private String technicalToken() {
        try {
            return partners.accessToken();
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail validation(IllegalArgumentException invalid) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }
}
