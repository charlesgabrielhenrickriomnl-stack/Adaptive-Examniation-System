package com.exam.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.exam.config.AcademicCatalog;
import com.exam.entity.User;
import com.exam.repository.UserRepository;

@Service
public class UserService {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    
    @Autowired
    private EmailService emailService;
    
    // School email domain pattern - accepts any .edu or .edu.xx domain
    private static final Pattern SCHOOL_EMAIL_PATTERN = 
        Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.edu(\\.[a-z]{2})?$", Pattern.CASE_INSENSITIVE);
    
    /**
     * Register a new student (must use school email)
     */
    public String registerStudent(String email,
                                  String password,
                                  String fullName,
                                  String schoolName,
                                  String campusName,
                                  String departmentName,
                                  String programName) {
        // Validate school email
        if (!isValidSchoolEmail(email)) {
            return "ERROR: Students must use a school email address (@student.school.edu)";
        }
        
        // Check if email already exists
        if (userRepository.existsByEmail(email)) {
            return "ERROR: Email already registered";
        }
        
        // Create new student user
        User student = new User();
        student.setEmail(email);
        student.setPassword(passwordEncoder.encode(password));
        student.setFullName(fullName);
        student.setSchoolName(schoolName);
        student.setCampusName(campusName);
        student.setDepartmentName(departmentName);
        student.setProgramName(programName);
        student.setRole(User.Role.STUDENT);
        student.setEnabled(false);

        String verificationToken = issueVerificationToken(student);
        
        userRepository.save(student);
        boolean emailSent = emailService.sendVerificationEmail(email, fullName, verificationToken);
        if (!emailSent) {
            return "WARN: Account created, but we could not send the verification email link. Please try again later.";
        }

        return "SUCCESS: Registration successful! Check your email and click the verification link before logging in.";
    }
    
    /**
     * Register a new teacher (any valid email)
     */
    public String registerTeacher(String email,
                                  String password,
                                  String fullName,
                                  String schoolName,
                                  String campusName,
                                  String departmentName,
                                  String programName) {
        // Check if email already exists
        if (userRepository.existsByEmail(email)) {
            return "ERROR: Email already registered";
        }
        
        // Create new teacher user
        User teacher = new User();
        teacher.setEmail(email);
        teacher.setPassword(passwordEncoder.encode(password));
        teacher.setFullName(fullName);
        teacher.setSchoolName(schoolName);
        teacher.setCampusName(campusName);
        teacher.setDepartmentName(departmentName);
        teacher.setProgramName(programName);
        teacher.setRole(User.Role.TEACHER);
        teacher.setEnabled(false);

        String verificationToken = issueVerificationToken(teacher);
        
        userRepository.save(teacher);
        boolean emailSent = emailService.sendVerificationEmail(email, fullName, verificationToken);
        if (!emailSent) {
            return "WARN: Account created, but we could not send the verification email link. Please try again later.";
        }

        return "SUCCESS: Registration successful! Check your email and click the verification link before logging in.";
    }

    public String registerDepartmentAdmin(String email,
                                          String password,
                                          String fullName,
                                          String schoolName,
                                          String campusName,
                                          String departmentName,
                                          String programName) {
        if (userRepository.existsByEmail(email)) {
            return "ERROR: Email already registered";
        }

        User departmentAdmin = new User();
        departmentAdmin.setEmail(email);
        departmentAdmin.setPassword(passwordEncoder.encode(password));
        departmentAdmin.setFullName(fullName);
        departmentAdmin.setSchoolName(schoolName);
        departmentAdmin.setCampusName(campusName);
        departmentAdmin.setDepartmentName(departmentName);
        departmentAdmin.setProgramName(programName);
        departmentAdmin.setRole(User.Role.DEPARTMENT_ADMIN);
        departmentAdmin.setEnabled(true);
        departmentAdmin.setVerificationToken(null);
        departmentAdmin.setVerificationCodeHash(null);
        departmentAdmin.setVerificationCodeExpiresAt(null);
        departmentAdmin.setVerificationCodeSentAt(null);

        userRepository.save(departmentAdmin);
        return "SUCCESS: Registration successful! You can now log in.";
    }
    
    /**
     * Verify user email with token
     */
    public boolean verifyEmail(String token) {
        Optional<User> userOpt = userRepository.findByVerificationToken(token);
        
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setEnabled(true);
            user.setVerificationToken(null); // Clear the token after verification
            user.setVerificationCodeHash(null);
            user.setVerificationCodeExpiresAt(null);
            user.setVerificationCodeSentAt(null);
            userRepository.save(user);
            return true;
        }
        
        return false;
    }

    public VerificationLinkResult resendVerificationLink(String email) {
        String normalizedEmail = normalizeValue(email).toLowerCase(Locale.ROOT);
        if (normalizedEmail.isBlank()) {
            return VerificationLinkResult.invalid("Enter your email to resend the verification link.");
        }

        Optional<User> userOpt = userRepository.findByEmail(normalizedEmail);
        if (userOpt.isEmpty()) {
            return VerificationLinkResult.invalid("No account found for that email.");
        }

        User user = userOpt.get();
        if (user.isEnabled()) {
            return VerificationLinkResult.alreadyVerified("This account is already verified. You can log in now.");
        }

        String verificationToken = issueVerificationToken(user);
        userRepository.save(user);
        boolean sent = emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), verificationToken);
        if (!sent) {
            return VerificationLinkResult.invalid("Unable to send verification link right now. Please try again in a moment.");
        }

        return VerificationLinkResult.success("Verification link sent to your email.");
    }
    
    /**
     * Validate school email format
     */
    public boolean isValidSchoolEmail(String email) {
        return SCHOOL_EMAIL_PATTERN.matcher(email).matches();
    }
    
    /**
     * Find user by email
     */
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }
    
    /**
     * Authenticate user
     */
    public boolean authenticate(String email, String password) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            return passwordEncoder.matches(password, user.getPassword()) && user.isEnabled();
        }
        return false;
    }

    public boolean updateAffiliation(String email,
                                     String schoolName,
                                     String campusName,
                                     String departmentName,
                                     String programName) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();
        user.setSchoolName(schoolName);
        user.setCampusName(campusName);
        user.setDepartmentName(departmentName);
        user.setProgramName(programName);
        userRepository.save(user);
        return true;
    }

    public List<String> getRegistrationDepartments() {
        return new ArrayList<>(getRegistrationProgramsByDepartment().keySet());
    }

    public Map<String, List<String>> getRegistrationProgramsByDepartment() {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        AcademicCatalog.PROGRAMS_BY_DEPARTMENT.forEach((department, programs) ->
            merged.put(department, new ArrayList<>(programs))
        );

        for (User user : userRepository.findAll()) {
            if (user == null) {
                continue;
            }

            String department = normalizeValue(user.getDepartmentName());
            if (department.isBlank()) {
                continue;
            }

            List<String> programs = merged.computeIfAbsent(department, key -> new ArrayList<>());
            String program = normalizeValue(user.getProgramName());
            if (!program.isBlank() && programs.stream().noneMatch(existing -> existing.equalsIgnoreCase(program))) {
                programs.add(program);
            }
        }

        return merged;
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private String issueVerificationToken(User user) {
        String token = UUID.randomUUID().toString();
        user.setVerificationToken(token);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationCodeSentAt(null);
        return token;
    }

    public record VerificationLinkResult(boolean success, boolean alreadyVerified, String message) {
        public static VerificationLinkResult success(String message) {
            return new VerificationLinkResult(true, false, message);
        }

        public static VerificationLinkResult invalid(String message) {
            return new VerificationLinkResult(false, false, message);
        }

        public static VerificationLinkResult alreadyVerified(String message) {
            return new VerificationLinkResult(false, true, message);
        }
    }
}
