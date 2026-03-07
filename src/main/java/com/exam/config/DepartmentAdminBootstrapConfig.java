package com.exam.config;

import com.exam.entity.User;
import com.exam.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@DependsOn("userEnabledBackfillConfig")
public class DepartmentAdminBootstrapConfig {

    @Value("${app.department-admin.email:${DEPARTMENT_ADMIN_EMAIL:department.admin@eac.local}}")
    private String adminEmail;

    @Value("${app.department-admin.password:${DEPARTMENT_ADMIN_PASSWORD:Admin123!}}")
    private String adminPassword;

    @Value("${app.department-admin.full-name:Department Administrator}")
    private String adminFullName;

    @Value("${app.department-admin.department:Engineering and Technology}")
    private String adminDepartment;

    @Value("${app.department-admin.program:BS Information Technology}")
    private String adminProgram;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @PostConstruct
    public void ensureBuiltInDepartmentAdmin() {
        if (adminEmail == null || adminEmail.isBlank() || adminPassword == null || adminPassword.isBlank()) {
            return;
        }

        User existing = userRepository.findByEmail(adminEmail.trim()).orElse(null);
        if (existing != null) {
            boolean changed = false;
            if (existing.getRole() != User.Role.DEPARTMENT_ADMIN) {
                existing.setRole(User.Role.DEPARTMENT_ADMIN);
                changed = true;
            }
            if (!existing.isEnabled()) {
                existing.setEnabled(true);
                changed = true;
            }
            if (changed) {
                userRepository.save(existing);
            }
            return;
        }

        User departmentAdmin = new User();
        departmentAdmin.setEmail(adminEmail.trim());
        departmentAdmin.setPassword(passwordEncoder.encode(adminPassword));
        departmentAdmin.setFullName(adminFullName == null || adminFullName.isBlank() ? "Department Administrator" : adminFullName.trim());
        departmentAdmin.setSchoolName(AcademicCatalog.SCHOOL_NAME);
        departmentAdmin.setCampusName(AcademicCatalog.CAMPUS_NAME);
        departmentAdmin.setDepartmentName(adminDepartment == null || adminDepartment.isBlank() ? "Engineering and Technology" : adminDepartment.trim());
        departmentAdmin.setProgramName(adminProgram == null ? "" : adminProgram.trim());
        departmentAdmin.setRole(User.Role.DEPARTMENT_ADMIN);
        departmentAdmin.setEnabled(true);
        departmentAdmin.setVerificationToken(null);
        userRepository.save(departmentAdmin);
    }
}
