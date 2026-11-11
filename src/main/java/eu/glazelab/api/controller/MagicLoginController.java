package eu.glazelab.api.controller;

import eu.glazelab.api.controller.AuthController.EmailInput;
import eu.glazelab.api.controller.AuthController.ConfirmationInput;
import eu.glazelab.api.model.AccountToken.Purpose;
import eu.glazelab.api.security.SessionLogin;
import eu.glazelab.api.service.AccountMail;
import eu.glazelab.api.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/auth/magic")
@RequiredArgsConstructor
public class MagicLoginController {
    private final AccountService accounts;
    private final AccountMail mail;
    private final SessionLogin sessions;
    @PostMapping("/request")
    void requestMagicLink(@Valid @RequestBody EmailInput input) {
        if (!mail.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, AccountService.EMAIL_NOT_CONFIGURED);
        accounts.request(input.email(), Purpose.MAGIC_LOGIN);
    }
    @PostMapping("/confirm")
    void confirmMagicLink(@Valid @RequestBody ConfirmationInput input, HttpServletRequest request, HttpServletResponse response) {
        sessions.signIn(accounts.magicLogin(input.token(), input.password(), input.displayName(),
                new AccountService.Consent(input.termsAccepted(), input.termsVersion(), input.diagnosticsOptIn())), request, response);
    }
}
