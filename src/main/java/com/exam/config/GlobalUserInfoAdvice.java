package com.exam.config;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.exam.entity.User;
import com.exam.repository.UserRepository;

/**
 * Exposes authenticated user's information (full name, email) to all Thymeleaf views.
 */
@ControllerAdvice
@Component
public class GlobalUserInfoAdvice {

    @Autowired
    private UserRepository userRepository;

    /**
     * Make the current user's full name available as "currentUserFullName" in all models.
     */
    @ModelAttribute("currentUserFullName")
    public String currentUserFullName(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .map(User::getFullName)
                .orElse(email);
    }

    /**
     * Optionally expose the current user's email as well.
     */
    @ModelAttribute("currentUserEmail")
    public String currentUserEmail(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getName();
    }

    @ModelAttribute("currentUserRole")
    public String currentUserRole(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .map(User::getRole)
                .map(Enum::name)
                .orElse(null);
    }

    @ModelAttribute("currentUserSchool")
    public String currentUserSchool(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .map(User::getSchoolName)
            .orElse(AcademicCatalog.SCHOOL_NAME);
    }

    @ModelAttribute("currentUserCampus")
    public String currentUserCampus(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .map(User::getCampusName)
            .orElse(AcademicCatalog.CAMPUS_NAME);
    }

    @ModelAttribute("currentUserDepartment")
    public String currentUserDepartment(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .map(User::getDepartmentName)
                .orElse(null);
    }

    @ModelAttribute("currentUserProgram")
    public String currentUserProgram(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        String email = authentication.getName();
        return userRepository.findByEmail(email)
                .map(User::getProgramName)
                .orElse(null);
    }

    @ModelAttribute("departmentOptions")
    public List<String> departmentOptions() {
        return AcademicCatalog.DEPARTMENTS;
    }

    @ModelAttribute("programOptionsByDepartment")
    public Map<String, List<String>> programOptionsByDepartment() {
        return AcademicCatalog.PROGRAMS_BY_DEPARTMENT;
    }
}
