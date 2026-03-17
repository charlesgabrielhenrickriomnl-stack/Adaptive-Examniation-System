package com.exam.config;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import com.exam.entity.User;
import com.exam.repository.UserRepository;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class CustomAuthenticationFailureHandler implements AuthenticationFailureHandler {

    @Autowired
    private UserRepository userRepository;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {

        String email = Optional.ofNullable(request.getParameter("username")).orElse("").trim();
        String password = Optional.ofNullable(request.getParameter("password")).orElse("");

        String field = "email";
        String message = "Invalid email address.";

        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            String encodedUsername = URLEncoder.encode(email, StandardCharsets.UTF_8);
            response.sendRedirect("/login?username=" + encodedUsername + "&unregistered=true");
            return;
        } else {
            User user = userOpt.get();
            if (!user.isEnabled()) {
                String encodedField = URLEncoder.encode("email", StandardCharsets.UTF_8);
                String encodedMessage = URLEncoder.encode("Account is not verified yet. Check your email and click the verification link before login.", StandardCharsets.UTF_8);
                String encodedUsername = URLEncoder.encode(user.getEmail(), StandardCharsets.UTF_8);
                response.sendRedirect("/login?error=true&errorField=" + encodedField + "&errorMessage=" + encodedMessage + "&username=" + encodedUsername);
                return;
            } else if (!passwordEncoder.matches(password, user.getPassword())) {
                field = "password";
                message = "Wrong password.";
            } else if (exception instanceof BadCredentialsException) {
                field = "email";
                message = "Invalid login credentials.";
            }
        }

        String encodedField = URLEncoder.encode(field, StandardCharsets.UTF_8);
        String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
        String encodedUsername = URLEncoder.encode(email, StandardCharsets.UTF_8);

        String redirectUrl = "/login?error=true"
            + "&errorField=" + encodedField
            + "&errorMessage=" + encodedMessage
            + "&username=" + encodedUsername
            + "&step=password";

        response.sendRedirect(redirectUrl);
    }
}
