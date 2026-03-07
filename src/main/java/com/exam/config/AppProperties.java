package com.exam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String baseUrl;
    private DepartmentAdmin departmentAdmin = new DepartmentAdmin();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public DepartmentAdmin getDepartmentAdmin() {
        return departmentAdmin;
    }

    public void setDepartmentAdmin(DepartmentAdmin departmentAdmin) {
        this.departmentAdmin = departmentAdmin;
    }

    public static class DepartmentAdmin {
        private String email;
        private String password;
        private String fullName;
        private String department;
        private String program;

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getDepartment() {
            return department;
        }

        public void setDepartment(String department) {
            this.department = department;
        }

        public String getProgram() {
            return program;
        }

        public void setProgram(String program) {
            this.program = program;
        }
    }
}
