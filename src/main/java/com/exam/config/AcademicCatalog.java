package com.exam.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AcademicCatalog {

    public static final String SCHOOL_NAME = "Emilio Aguinaldo College";
    public static final String CAMPUS_NAME = "Manila";

    public static final List<String> DEPARTMENTS = List.of(
        "ETEEAP",
        "Arts & Sciences",
        "Business Education",
        "Criminology",
        "Dentistry",
        "Engineering and Technology",
        "Graduate School",
        "Hospitality and Tourism Management",
        "Marian School of Nursing",
        "Medical Technology",
        "Medicine",
        "Midwifery & Caregiving",
        "Pharmacy",
        "Physical, Occupational and Respiratory Therapy",
        "Radiologic Technology",
        "Teacher Education"
    );

    public static final Map<String, List<String>> PROGRAMS_BY_DEPARTMENT = buildProgramsByDepartment();

    private AcademicCatalog() {
    }

    public static List<String> programsForDepartment(String departmentName) {
        if (departmentName == null || departmentName.isBlank()) {
            return List.of();
        }
        return PROGRAMS_BY_DEPARTMENT.getOrDefault(departmentName.trim(), List.of());
    }

    public static boolean isValidDepartment(String departmentName) {
        return departmentName != null && DEPARTMENTS.contains(departmentName.trim());
    }

    public static boolean isValidProgram(String departmentName, String programName) {
        List<String> programs = programsForDepartment(departmentName);
        if (programs.isEmpty()) {
            return programName == null || programName.trim().isBlank();
        }
        return programName != null && programs.contains(programName.trim());
    }

    private static Map<String, List<String>> buildProgramsByDepartment() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("ETEEAP", List.of(
            "ETEEAP - BS Information Technology",
            "ETEEAP - BS Criminology",
            "ETEEAP - BS Hospitality Management"
        ));
        map.put("Arts & Sciences", List.of(
            "BA Communication",
            "BS Psychology"
        ));
        map.put("Business Education", List.of(
            "BS Business Administration",
            "BS Accountancy"
        ));
        map.put("Criminology", List.of(
            "BS Criminology"
        ));
        map.put("Dentistry", List.of(
            "Doctor of Dental Medicine"
        ));
        map.put("Engineering and Technology", List.of(
            "BS Computer Engineering",
            "BS Information Technology",
            "BS Civil Engineering",
            "BS Mechanical Engineering",
            "BS Electronics Engineering"
        ));
        map.put("Graduate School", List.of(
            "Master in Business Administration",
            "Master of Arts in Education",
            "Master of Science in Nursing"
        ));
        map.put("Hospitality and Tourism Management", List.of(
            "BS Hospitality Management",
            "BS Tourism Management"
        ));
        map.put("Marian School of Nursing", List.of(
            "BS Nursing"
        ));
        map.put("Medical Technology", List.of(
            "BS Medical Technology"
        ));
        map.put("Medicine", List.of(
            "Doctor of Medicine"
        ));
        map.put("Midwifery & Caregiving", List.of(
            "BS Midwifery",
            "Caregiving NC II"
        ));
        map.put("Pharmacy", List.of(
            "BS Pharmacy"
        ));
        map.put("Physical, Occupational and Respiratory Therapy", List.of(
            "BS Physical Therapy",
            "BS Occupational Therapy",
            "BS Respiratory Therapy"
        ));
        map.put("Radiologic Technology", List.of(
            "BS Radiologic Technology"
        ));
        map.put("Teacher Education", List.of(
            "Bachelor of Secondary Education",
            "Bachelor of Elementary Education"
        ));
        return Collections.unmodifiableMap(map);
    }
}
