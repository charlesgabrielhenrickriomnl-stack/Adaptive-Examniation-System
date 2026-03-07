package com.exam.Controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.exam.entity.User;
import com.exam.repository.UserRepository;
import com.exam.service.FaceVerificationService;
import com.exam.service.FaceVerificationService.FaceVerificationException;
import com.exam.service.FaceVerificationSessionKeys;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/student/face-verification")
public class StudentFaceVerificationController {

    private final UserRepository userRepository;
    private final FaceVerificationService faceVerificationService;

    public StudentFaceVerificationController(UserRepository userRepository,
                                            FaceVerificationService faceVerificationService) {
        this.userRepository = userRepository;
        this.faceVerificationService = faceVerificationService;
    }

    @GetMapping
    public String showPage(Principal principal,
                           HttpSession session,
                           HttpServletRequest request,
                           @RequestParam(name = "error", required = false) String error,
                           Model model) {
        User user = resolveCurrentStudent(principal);
        if (user == null) {
            return "redirect:/login";
        }

        if (!faceVerificationService.isFeatureEnabled()) {
            session.setAttribute(FaceVerificationSessionKeys.FACE_VERIFIED, Boolean.TRUE);
            return "redirect:" + resolvePostVerifyRedirect(session);
        }

        ensurePendingSessionState(user, session, request);
        String resolvedError = error;

        model.addAttribute("setupMode", !faceVerificationService.userHasReferenceFace(user));
        model.addAttribute("faceApiConfigured", true);
        model.addAttribute("faceModelUrl", faceVerificationService.getModelUrl());
        model.addAttribute("errorMessage", resolvedError);
        model.addAttribute("studentName", user.getFullName());
        model.addAttribute("studentEmail", user.getEmail());
        return "student-face-verification";
    }

    @PostMapping({"", "/confirm"})
    public String confirmFace(Principal principal,
                              HttpSession session,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              @RequestParam("capturedImage") String capturedImageBase64,
                              @RequestParam("capturedDescriptor") String capturedDescriptor,
                              @RequestParam(name = "trustDevice", required = false, defaultValue = "false") boolean trustDevice) {
        User user = resolveCurrentStudent(principal);
        if (user == null) {
            return "redirect:/login";
        }

        if (!faceVerificationService.isFeatureEnabled()) {
            session.setAttribute(FaceVerificationSessionKeys.FACE_VERIFIED, Boolean.TRUE);
            return "redirect:" + resolvePostVerifyRedirect(session);
        }

        ensurePendingSessionState(user, session, request);
        Object tokenAttr = session.getAttribute(FaceVerificationSessionKeys.PENDING_FACE_DEVICE_TOKEN);
        String deviceToken = tokenAttr instanceof String value && !value.isBlank()
            ? value
            : faceVerificationService.generateDeviceToken();
        session.setAttribute(FaceVerificationSessionKeys.PENDING_FACE_DEVICE_TOKEN, deviceToken);

        try {
            if (!faceVerificationService.userHasReferenceFace(user)) {
                faceVerificationService.validateDescriptorPayload(capturedDescriptor);
                faceVerificationService.saveReferenceFace(user, capturedImageBase64, capturedDescriptor);
            } else {
                var result = faceVerificationService.verifyCapturedDescriptor(user, capturedDescriptor);
                if (!result.matched()) {
                    String message = URLEncoder.encode(
                            "Face verification failed. Distance: " + String.format("%.3f", result.distance())
                                + " (threshold " + String.format("%.3f", result.threshold()) + ")",
                            StandardCharsets.UTF_8);
                    return "redirect:/student/face-verification?error=" + message;
                }
            }

            if (trustDevice) {
                faceVerificationService.trustDevice(user, deviceToken, request.getHeader("User-Agent"));
            }
            session.setAttribute(FaceVerificationSessionKeys.FACE_VERIFIED, Boolean.TRUE);
            session.removeAttribute(FaceVerificationSessionKeys.PENDING_FACE_USER_ID);
            session.removeAttribute(FaceVerificationSessionKeys.PENDING_FACE_DEVICE_TOKEN);

            if (trustDevice) {
                writeTrustedDeviceCookie(response, request.isSecure(), deviceToken);
            }
            return "redirect:" + resolvePostVerifyRedirect(session);
        } catch (FaceVerificationException ex) {
            String safeMessage = ex.getMessage() == null ? "Face verification failed." : ex.getMessage();
            String message = URLEncoder.encode(safeMessage, StandardCharsets.UTF_8);
            return "redirect:/student/face-verification?error=" + message;
        }
    }

    private User resolveCurrentStudent(Principal principal) {
        if (principal == null) {
            return null;
        }

        Optional<User> userOpt = userRepository.findByEmail(principal.getName());
        if (userOpt.isEmpty() || userOpt.get().getRole() != User.Role.STUDENT) {
            return null;
        }
        return userOpt.get();
    }

    private void ensurePendingSessionState(User user, HttpSession session, HttpServletRequest request) {
        Object currentPendingUserId = session.getAttribute(FaceVerificationSessionKeys.PENDING_FACE_USER_ID);
        if (!(currentPendingUserId instanceof Number) || ((Number) currentPendingUserId).longValue() != user.getId()) {
            session.setAttribute(FaceVerificationSessionKeys.PENDING_FACE_USER_ID, user.getId());
        }

        Object existingToken = session.getAttribute(FaceVerificationSessionKeys.PENDING_FACE_DEVICE_TOKEN);
        if (!(existingToken instanceof String) || ((String) existingToken).isBlank()) {
            String token = faceVerificationService.resolveDeviceToken(request)
                    .orElseGet(faceVerificationService::generateDeviceToken);
            session.setAttribute(FaceVerificationSessionKeys.PENDING_FACE_DEVICE_TOKEN, token);
        }
    }

    private void writeTrustedDeviceCookie(HttpServletResponse response, boolean secureRequest, String deviceToken) {
        long maxAgeSeconds = Duration.ofDays(faceVerificationService.getTrustedDeviceDays()).toSeconds();
        String cookieName = Objects.requireNonNullElse(faceVerificationService.getCookieName(), "exam_trusted_device");
        String safeToken = Objects.requireNonNullElse(deviceToken, "");

        ResponseCookie cookie = ResponseCookie.from(cookieName, safeToken)
                .httpOnly(true)
                .secure(secureRequest)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();

        response.addHeader("Set-Cookie", cookie.toString());
    }

    private String resolvePostVerifyRedirect(HttpSession session) {
        Object pendingExamId = session.getAttribute(FaceVerificationSessionKeys.PENDING_FACE_EXAM_ID);
        if (pendingExamId != null) {
            session.setAttribute(FaceVerificationSessionKeys.FACE_VERIFIED_EXAM_ID, pendingExamId);
            session.removeAttribute(FaceVerificationSessionKeys.PENDING_FACE_EXAM_ID);
        }

        Object nextRedirect = session.getAttribute(FaceVerificationSessionKeys.PENDING_FACE_REDIRECT_URL);
        session.removeAttribute(FaceVerificationSessionKeys.PENDING_FACE_REDIRECT_URL);
        if (nextRedirect instanceof String redirect && !redirect.isBlank() && redirect.startsWith("/")) {
            return redirect;
        }
        return "/student/dashboard";
    }
}
