package com.exam.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class UserEnabledBackfillConfig {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void backfillNullEnabledUsers() {
        ensureRoleColumnSupportsDepartmentAdmin();
        jdbcTemplate.update("UPDATE users SET enabled = 1 WHERE enabled IS NULL");
    }

    private void ensureRoleColumnSupportsDepartmentAdmin() {
        try {
            jdbcTemplate.execute("ALTER TABLE users MODIFY COLUMN role VARCHAR(50) NOT NULL");
        } catch (DataAccessException ignored) {
            // Column may already be compatible depending on DB state.
        }
    }
}
