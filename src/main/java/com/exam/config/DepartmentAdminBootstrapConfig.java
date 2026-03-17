package com.exam.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.exam.entity.DepartmentProgram;
import com.exam.entity.User;
import com.exam.repository.DepartmentProgramRepository;
import com.exam.repository.UserRepository;

import jakarta.annotation.PostConstruct;

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

    @Autowired
    private DepartmentProgramRepository departmentProgramRepository;

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
            ensureDepartmentPrograms(existing.getDepartmentName(), existing.getEmail());
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
        ensureDepartmentPrograms(departmentAdmin.getDepartmentName(), departmentAdmin.getEmail());
    }

    private void ensureDepartmentPrograms(String departmentName, String createdByEmail) {
        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        if (normalizedDepartment.isBlank()) {
            return;
        }

        List<String> catalogPrograms = AcademicCatalog.programsForDepartment(normalizedDepartment);
        if (catalogPrograms == null || catalogPrograms.isEmpty()) {
            return;
        }

        Set<String> existingPrograms = departmentProgramRepository
            .findByDepartmentNameIgnoreCaseOrderByProgramNameAsc(normalizedDepartment)
            .stream()
            .map(DepartmentProgram::getProgramName)
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase())
            .collect(Collectors.toSet());

        List<DepartmentProgram> toInsert = new ArrayList<>();
        for (String programName : catalogPrograms) {
            String normalizedProgram = programName == null ? "" : programName.trim();
            if (normalizedProgram.isBlank()) {
                continue;
            }

            if (existingPrograms.add(normalizedProgram.toLowerCase())) {
                DepartmentProgram row = new DepartmentProgram();
                row.setDepartmentName(normalizedDepartment);
                row.setProgramName(normalizedProgram);
                row.setCreatedByEmail(createdByEmail == null ? "system" : createdByEmail.trim());
                toInsert.add(row);
            }
        }

        if (!toInsert.isEmpty()) {
            departmentProgramRepository.saveAll(toInsert);
        }
    }
}
