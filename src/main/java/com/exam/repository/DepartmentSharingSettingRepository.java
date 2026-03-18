package com.exam.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.exam.entity.DepartmentSharingSetting;

@Repository
public interface DepartmentSharingSettingRepository extends JpaRepository<DepartmentSharingSetting, Long> {
    Optional<DepartmentSharingSetting> findByDepartmentNameIgnoreCase(String departmentName);
}
