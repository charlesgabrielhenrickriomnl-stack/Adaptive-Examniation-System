package com.exam.Controller;

import java.security.Principal;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.exam.config.AcademicCatalog;
import com.exam.entity.User;
import com.exam.service.UserService;

@Controller
public class AuthController {

    @Autowired
    private UserService userService;

    @GetMapping("/")
    public String index() {
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String showLoginPage(@RequestParam(name = "error", required = false) String error,
                                @RequestParam(name = "errorField", required = false) String errorField,
                                @RequestParam(name = "errorMessage", required = false) String errorMessage,
                                @RequestParam(name = "successMessage", required = false) String successMessage,
                                @RequestParam(name = "verifyResult", required = false) String verifyResult,
                                @RequestParam(name = "username", required = false) String username,
                                @RequestParam(name = "unregistered", required = false) String unregistered,
                                @RequestParam(name = "logout", required = false) String logout,
                                @RequestParam(name = "registered", required = false) String registered,
                                @RequestParam(name = "pendingVerification", required = false) String pendingVerification,
                                @RequestParam(name = "verified", required = false) String verified,
                                Model model) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (!normalizedUsername.isBlank()) {
            model.addAttribute("username", normalizedUsername);
        }

        if (error != null || unregistered != null) {
            model.addAttribute("error", true);
            model.addAttribute("errorField", errorField);

            if (unregistered != null) {
                model.addAttribute("errorField", "email");
                model.addAttribute("errorMessage", "Email is not registered. Please create an account first.");
                model.addAttribute("showSignupHint", true);
            } else {
                model.addAttribute("errorMessage", errorMessage != null ? errorMessage : "Invalid email or password");
            }
        }

        addIfNotNull(model, logout, "success", "You have been logged out successfully");
        if (pendingVerification != null) {
            model.addAttribute("success", "Registration successful! Check your email and click the verification link before logging in.");
        } else {
            addIfNotNull(model, registered, "success", "Registration successful. You can now log in.");
        }
        if (successMessage != null && !successMessage.isBlank()) {
            model.addAttribute("success", successMessage);
        }
        addIfNotNull(model, verified, "success", "Account verified successfully. You can now log in.");

        if (verifyResult != null && !verifyResult.isBlank()) {
            String normalizedVerifyResult = verifyResult.trim().toLowerCase();
            if ("success".equals(normalizedVerifyResult)) {
                model.addAttribute("success", "Registration successful! Check your email and click the verification link before logging in.");
            } else {
                model.addAttribute("error", true);
                model.addAttribute("errorField", "email");
                model.addAttribute("errorMessage", "Verification link is invalid or expired.");
            }
        }

        return "login";
    }

    private void addIfNotNull(Model model,
                              String flag,
                              @NonNull String attributeName,
                              @NonNull String message) {
        if (flag != null) {
            model.addAttribute(attributeName, message);
        }
    }

    @GetMapping("/register")
    public String showRegistrationPage(Model model) {
        populateRegistrationDefaults(model);
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(@RequestParam("email") String email,
                               @RequestParam("password") String password,
                               @RequestParam("fullName") String fullName,
                               @RequestParam("role") String role,
                               @RequestParam(name = "schoolName", required = false) String schoolName,
                               @RequestParam(name = "campusName", required = false) String campusName,
                               @RequestParam(name = "departmentName", required = false) String departmentName,
                               @RequestParam(name = "programName", required = false) String programName,
                               Model model,
                               RedirectAttributes redirectAttributes) {
        String normalizedSchool = (schoolName == null || schoolName.isBlank()) ? AcademicCatalog.SCHOOL_NAME : schoolName.trim();
        String normalizedCampus = (campusName == null || campusName.isBlank()) ? AcademicCatalog.CAMPUS_NAME : campusName.trim();
        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        String normalizedProgram = programName == null ? "" : programName.trim();
        boolean programRequired = "STUDENT".equalsIgnoreCase(role) || "TEACHER".equalsIgnoreCase(role);

        if (normalizedDepartment.isBlank()) {
            model.addAttribute("error", "Please select your department.");
            populateRegistrationDefaults(model);
            return "register";
        }

        if (programRequired && normalizedProgram.isBlank()) {
            model.addAttribute("error", "Please select your program.");
            populateRegistrationDefaults(model);
            return "register";
        }

        String result = switch (role) {
            case "STUDENT" -> userService.registerStudent(
                email,
                password,
                fullName,
                normalizedSchool,
                normalizedCampus,
                normalizedDepartment,
                normalizedProgram
            );
            case "TEACHER" -> userService.registerTeacher(
                email,
                password,
                fullName,
                normalizedSchool,
                normalizedCampus,
                normalizedDepartment,
                normalizedProgram
            );
            case "DEPARTMENT_ADMIN" -> userService.registerDepartmentAdmin(
                email,
                password,
                fullName,
                normalizedSchool,
                normalizedCampus,
                normalizedDepartment,
                normalizedProgram
            );
            default -> {
                model.addAttribute("error", "Invalid role selected");
                populateRegistrationDefaults(model);
                yield null;
            }
        };

        if (result == null) {
            return "register";
        }

        if (result.startsWith("ERROR")) {
            model.addAttribute("error", result.replace("ERROR: ", ""));
            populateRegistrationDefaults(model);
            return "register";
        }

        if (result.startsWith("WARN")) {
            model.addAttribute("error", result.replace("WARN: ", ""));
            populateRegistrationDefaults(model);
            return "register";
        }

        if ("TEACHER".equalsIgnoreCase(role) || "STUDENT".equalsIgnoreCase(role)) {
            redirectAttributes.addAttribute("registered", "true");
            redirectAttributes.addAttribute("pendingVerification", "true");
            redirectAttributes.addAttribute("username", email == null ? "" : email.trim().toLowerCase());
            return "redirect:/login";
        }

        return "redirect:/login?registered=true";
    }

    @PostMapping("/login/check-email")
    public String checkLoginEmail(@RequestParam("email") String email,
                                  RedirectAttributes redirectAttributes) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (normalizedEmail.isBlank()) {
            redirectAttributes.addAttribute("error", "true");
            redirectAttributes.addAttribute("errorField", "email");
            redirectAttributes.addAttribute("errorMessage", "Please enter your email address.");
            return "redirect:/login";
        }

        Optional<User> userOpt = userService.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            redirectAttributes.addAttribute("username", normalizedEmail);
            redirectAttributes.addAttribute("unregistered", "true");
            return "redirect:/login";
        }

        User user = userOpt.get();
        if (user.isEnabled()) {
            redirectAttributes.addAttribute("username", user.getEmail());
            redirectAttributes.addAttribute("success", "Email recognized. Continue logging in.");
            return "redirect:/login";
        }

        UserService.VerificationLinkResult sendResult = userService.resendVerificationLink(user.getEmail());
        redirectAttributes.addAttribute("username", user.getEmail());
        if (sendResult.success()) {
            redirectAttributes.addAttribute("error", "true");
            redirectAttributes.addAttribute("errorField", "email");
            redirectAttributes.addAttribute("errorMessage", "Account is not verified yet. We sent a verification link to your email. Verify before logging in.");
            return "redirect:/login";
        }

        if (sendResult.alreadyVerified()) {
            return "redirect:/login";
        }

        redirectAttributes.addAttribute("error", "true");
        redirectAttributes.addAttribute("errorField", "email");
        redirectAttributes.addAttribute("errorMessage", sendResult.message());
        return "redirect:/login";
    }

    @GetMapping("/verify")
    public String showVerifyEmailPage(@RequestParam(name = "token", required = false) String token,
                                      RedirectAttributes redirectAttributes) {
        if (token != null && !token.isBlank()) {
            boolean verified = userService.verifyEmail(token.trim());
            if (verified) {
                return "redirect:/login?verifyResult=success";
            }

            return "redirect:/login?verifyResult=failed";
        }

        return "redirect:/login";
    }

    @PostMapping("/verify/send")
    public String sendVerificationLink(@RequestParam(name = "email", required = false) String email,
                                       RedirectAttributes redirectAttributes) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase();
        if (normalizedEmail.isBlank()) {
            redirectAttributes.addAttribute("error", "true");
            redirectAttributes.addAttribute("errorField", "email");
            redirectAttributes.addAttribute("errorMessage", "Enter your email to resend the verification link.");
            return "redirect:/login";
        }

        Optional<User> userOpt = userService.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            redirectAttributes.addAttribute("username", normalizedEmail);
            redirectAttributes.addAttribute("unregistered", "true");
            return "redirect:/login";
        }

        User user = userOpt.get();
        redirectAttributes.addAttribute("username", user.getEmail());
        if (user.isEnabled()) {
            redirectAttributes.addAttribute("verified", "true");
            return "redirect:/login";
        }

        UserService.VerificationLinkResult sendResult = userService.resendVerificationLink(user.getEmail());
        if (sendResult.success()) {
            redirectAttributes.addAttribute("successMessage", "Verification link sent. Check your email and verify before logging in.");
            return "redirect:/login";
        }

        redirectAttributes.addAttribute("error", "true");
        redirectAttributes.addAttribute("errorField", "email");
        redirectAttributes.addAttribute("errorMessage", sendResult.message());
        return "redirect:/login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Principal principal) {
        if (principal != null) {
            User user = userService.findByEmail(principal.getName()).orElse(null);
            if (user != null && user.getRole() == User.Role.TEACHER) {
                return "redirect:/teacher/homepage";
            }
            if (user != null && user.getRole() == User.Role.DEPARTMENT_ADMIN) {
                return "redirect:/department-admin/dashboard";
            }
            if (user != null && user.getRole() == User.Role.STUDENT) {
                return "redirect:/student/dashboard";
            }
        }
        return "redirect:/login";
    }

    @PostMapping("/account/update-affiliation")
    public String updateAffiliation(@RequestParam(name = "departmentName", required = false) String departmentName,
                                    @RequestParam(name = "programName", required = false) String programName,
                                    Principal principal,
                                    RedirectAttributes redirectAttributes) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return "redirect:/login";
        }

        User user = userService.findByEmail(principal.getName()).orElse(null);
        String redirectTo = "redirect:/student/profile";
        if (user != null && user.getRole() == User.Role.TEACHER) {
            redirectTo = "redirect:/teacher/department-dashboard";
        } else if (user != null && user.getRole() == User.Role.DEPARTMENT_ADMIN) {
            redirectTo = "redirect:/department-admin/dashboard";
        }

        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        String normalizedProgram = programName == null ? "" : programName.trim();
        if (normalizedDepartment.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select your department.");
            return redirectTo;
        }

        boolean programRequired = user == null || user.getRole() != User.Role.DEPARTMENT_ADMIN;
        if (programRequired && normalizedProgram.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select your program.");
            return redirectTo;
        }

        boolean updated = userService.updateAffiliation(
            principal.getName(),
            AcademicCatalog.SCHOOL_NAME,
            AcademicCatalog.CAMPUS_NAME,
            normalizedDepartment,
            normalizedProgram
        );

        if (!updated) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to update account details.");
            return redirectTo;
        }

        redirectAttributes.addFlashAttribute("successMessage", "Account affiliation updated.");
        return redirectTo;
    }

    private void populateRegistrationDefaults(Model model) {
        var programsByDepartment = userService.getRegistrationProgramsByDepartment();
        model.addAttribute("schoolName", AcademicCatalog.SCHOOL_NAME);
        model.addAttribute("campusName", AcademicCatalog.CAMPUS_NAME);
        model.addAttribute("departments", userService.getRegistrationDepartments());
        model.addAttribute("programsByDepartment", programsByDepartment);
    }
}
