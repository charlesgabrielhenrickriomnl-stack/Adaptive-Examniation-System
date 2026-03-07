package com.exam.service;

import com.exam.entity.User;
import com.exam.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

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
        student.setEnabled(false); // User must verify email first
        student.setVerificationToken(UUID.randomUUID().toString());
        
        userRepository.save(student);
        
        // Send verification email
        emailService.sendVerificationEmail(email, fullName, student.getVerificationToken());
        
        return "SUCCESS: Registration successful! Please check your email to verify your account.";
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
        teacher.setEnabled(false); // Teachers also need email verification
        teacher.setVerificationToken(UUID.randomUUID().toString());
        
        userRepository.save(teacher);
        
        // Send verification email
        emailService.sendVerificationEmail(email, fullName, teacher.getVerificationToken());
        
        return "SUCCESS: Registration successful! Please check your email to verify your account.";
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
        departmentAdmin.setEnabled(false);
        departmentAdmin.setVerificationToken(UUID.randomUUID().toString());

        userRepository.save(departmentAdmin);
        emailService.sendVerificationEmail(email, fullName, departmentAdmin.getVerificationToken());

        return "SUCCESS: Registration successful! Please check your email to verify your account.";
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
            userRepository.save(user);
            return true;
        }
        
        return false;
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
}
