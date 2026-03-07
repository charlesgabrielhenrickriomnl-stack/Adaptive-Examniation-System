package com.exam.Controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.exam.config.AcademicCatalog;
import com.exam.entity.QuestionBankItem;
import com.exam.entity.Subject;
import com.exam.entity.User;
import com.exam.repository.QuestionBankItemRepository;
import com.exam.repository.SubjectRepository;
import com.exam.repository.UserRepository;

@Controller
@RequestMapping("/department-admin")
public class DepartmentAdminController {

    private static final String DEFAULT_IMPORTED_STUDENT_PASSWORD = "Student123!";

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;
    private final QuestionBankItemRepository questionBankItemRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DepartmentAdminController(UserRepository userRepository,
                                     SubjectRepository subjectRepository,
                                     QuestionBankItemRepository questionBankItemRepository,
                                     BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.questionBankItemRepository = questionBankItemRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);

        String departmentName = currentAdmin == null || currentAdmin.getDepartmentName() == null
            ? ""
            : currentAdmin.getDepartmentName().trim();

        List<String> teacherEmailsInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> departmentName.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
            .map(User::getEmail)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();

        List<Subject> departmentSubjects = teacherEmailsInDepartment.isEmpty()
            ? new ArrayList<>()
            : subjectRepository.findAll().stream()
                .filter(subject -> subject != null && subject.getTeacherEmail() != null)
                .filter(subject -> teacherEmailsInDepartment.stream()
                    .anyMatch(email -> email.equalsIgnoreCase(subject.getTeacherEmail().trim())))
                .sorted(Comparator.comparing(Subject::getSubjectName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<String, User> teacherProfilesByEmail = new HashMap<>();
        if (!teacherEmailsInDepartment.isEmpty()) {
            for (User teacher : userRepository.findByEmailIn(teacherEmailsInDepartment)) {
                if (teacher != null && teacher.getEmail() != null) {
                    teacherProfilesByEmail.put(teacher.getEmail().trim().toLowerCase(), teacher);
                }
            }
        }

        List<QuestionBankItem> departmentQuestionBank = teacherEmailsInDepartment.isEmpty()
            ? new ArrayList<>()
            : questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .filter(item -> item.getSourceTeacherEmail() != null)
                .filter(item -> teacherEmailsInDepartment.stream()
                    .anyMatch(email -> email.equalsIgnoreCase(item.getSourceTeacherEmail().trim())))
                .toList();

        List<Map<String, Object>> departmentQuestionBankRows = departmentQuestionBank.stream()
            .map(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("subject", item.getSubject() == null ? "" : item.getSubject());
                row.put("sourceExamName", item.getSourceExamName() == null ? "" : item.getSourceExamName());
                row.put("difficulty", item.getDifficulty() == null ? "Medium" : item.getDifficulty());
                row.put("sourceTeacherEmail", item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail());
                String teacherEmail = item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail().trim().toLowerCase();
                User teacherProfile = teacherProfilesByEmail.get(teacherEmail);
                row.put("sourceTeacherDepartment", teacherProfile == null ? "" : (teacherProfile.getDepartmentName() == null ? "" : teacherProfile.getDepartmentName()));
                row.put("sourceTeacherProgram", teacherProfile == null ? "" : (teacherProfile.getProgramName() == null ? "" : teacherProfile.getProgramName()));
                row.put("questionPreview", toPreview(item.getQuestionText()));
                row.put("questionText", item.getQuestionText() == null ? "" : item.getQuestionText());
                row.put("createdAt", item.getCreatedAt());
                return row;
            })
            .toList();

        List<Map<String, Object>> dashboardQuestionPreviewRows = departmentQuestionBankRows.stream()
            .limit(8)
            .toList();

        Map<String, Long> subjectCountsByTeacher = departmentSubjects.stream()
            .filter(subject -> subject.getTeacherEmail() != null)
            .collect(Collectors.groupingBy(subject -> subject.getTeacherEmail().trim().toLowerCase(), Collectors.counting()));

        model.addAttribute("departmentName", departmentName.isBlank() ? "Department not set" : departmentName);
        model.addAttribute("departmentTeacherCount", teacherEmailsInDepartment.size());
        model.addAttribute("departmentSubjectCount", departmentSubjects.size());
        model.addAttribute("departmentQuestionCount", departmentQuestionBank.size());
        model.addAttribute("departmentQuestionBankRows", dashboardQuestionPreviewRows);
        model.addAttribute("departmentSubjects", departmentSubjects);
        model.addAttribute("teacherProfilesByEmail", teacherProfilesByEmail);
        model.addAttribute("subjectCountsByTeacher", subjectCountsByTeacher);
        return "department-admin-dashboard";
    }

    @PostMapping("/import-students")
    public String importStudents(@RequestParam("studentListFile") MultipartFile studentListFile,
                                 @RequestParam(name = "programName", required = false) String programName,
                                 Principal principal,
                                 RedirectAttributes redirectAttributes) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Department Admin can import students here.");
            return "redirect:/department-admin/dashboard";
        }

        String departmentName = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        if (departmentName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Department is not set for this admin account.");
            return "redirect:/department-admin/dashboard";
        }

        String normalizedProgram = programName == null ? "" : programName.trim();
        if (!AcademicCatalog.isValidProgram(departmentName, normalizedProgram)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid program for your department.");
            return "redirect:/department-admin/dashboard";
        }

        if (studentListFile == null || studentListFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please upload a CSV file.");
            return "redirect:/department-admin/dashboard";
        }

        int rowsRead = 0;
        int createdAccounts = 0;
        int updatedAccounts = 0;
        int skippedRows = 0;
        Set<String> seenEmails = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(studentListFile.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] columns = parseCsvRow(line);
                if (columns.length < 2) {
                    continue;
                }

                String rawName = columns[0] == null ? "" : columns[0].trim();
                String rawEmail = columns[1] == null ? "" : columns[1].trim().toLowerCase();
                String rawPassword = columns.length >= 3 && columns[2] != null ? columns[2].trim() : "";

                if (rowsRead == 0 && "email".equalsIgnoreCase(rawEmail)) {
                    rowsRead++;
                    continue;
                }
                rowsRead++;

                if (rawEmail.isBlank() || !rawEmail.contains("@") || !seenEmails.add(rawEmail)) {
                    skippedRows++;
                    continue;
                }

                String effectiveName = rawName.isBlank() ? rawEmail : rawName;
                String effectivePassword = rawPassword.isBlank() ? DEFAULT_IMPORTED_STUDENT_PASSWORD : rawPassword;

                User student = userRepository.findByEmail(rawEmail).orElse(null);
                if (student == null) {
                    student = new User();
                    student.setEmail(rawEmail);
                    student.setPassword(passwordEncoder.encode(effectivePassword));
                    student.setFullName(effectiveName);
                    student.setSchoolName(AcademicCatalog.SCHOOL_NAME);
                    student.setCampusName(AcademicCatalog.CAMPUS_NAME);
                    student.setDepartmentName(departmentName);
                    student.setProgramName(normalizedProgram);
                    student.setRole(User.Role.STUDENT);
                    student.setEnabled(true);
                    student.setVerificationToken(null);
                    userRepository.save(student);
                    createdAccounts++;
                } else if (student.getRole() != User.Role.STUDENT) {
                    skippedRows++;
                    continue;
                } else {
                    boolean changed = false;
                    if (!effectiveName.equals(student.getFullName())) {
                        student.setFullName(effectiveName);
                        changed = true;
                    }
                    if (!AcademicCatalog.SCHOOL_NAME.equals(student.getSchoolName())) {
                        student.setSchoolName(AcademicCatalog.SCHOOL_NAME);
                        changed = true;
                    }
                    if (!AcademicCatalog.CAMPUS_NAME.equals(student.getCampusName())) {
                        student.setCampusName(AcademicCatalog.CAMPUS_NAME);
                        changed = true;
                    }
                    if (!departmentName.equals(student.getDepartmentName())) {
                        student.setDepartmentName(departmentName);
                        changed = true;
                    }
                    if (!normalizedProgram.equals(student.getProgramName() == null ? "" : student.getProgramName())) {
                        student.setProgramName(normalizedProgram);
                        changed = true;
                    }
                    if (!student.isEnabled()) {
                        student.setEnabled(true);
                        changed = true;
                    }
                    if (!rawPassword.isBlank()) {
                        student.setPassword(passwordEncoder.encode(rawPassword));
                        changed = true;
                    }

                    if (changed) {
                        userRepository.save(student);
                        updatedAccounts++;
                    }
                }
            }
        } catch (IOException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to read the uploaded CSV file.");
            return "redirect:/department-admin/dashboard";
        }

        if (rowsRead == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No student rows found. Use CSV format: Full Name,Email,Password(optional).");
            return "redirect:/department-admin/dashboard";
        }

        String summary = "Import complete: "
            + createdAccounts + " account(s) created, "
            + updatedAccounts + " account(s) updated, "
            + skippedRows + " row(s) skipped.";
        redirectAttributes.addFlashAttribute("successMessage", summary);
        return "redirect:/department-admin/dashboard";
    }

    @GetMapping("/question-bank")
    public String questionBank(@RequestParam(name = "search", required = false) String search,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "15") int size,
                               Model model,
                               Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        String departmentName = currentAdmin == null || currentAdmin.getDepartmentName() == null
            ? ""
            : currentAdmin.getDepartmentName().trim();

        List<String> teacherEmailsInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> departmentName.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
            .map(User::getEmail)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();

        Map<String, User> teacherProfilesByEmail = new HashMap<>();
        if (!teacherEmailsInDepartment.isEmpty()) {
            for (User teacher : userRepository.findByEmailIn(teacherEmailsInDepartment)) {
                if (teacher != null && teacher.getEmail() != null) {
                    teacherProfilesByEmail.put(teacher.getEmail().trim().toLowerCase(), teacher);
                }
            }
        }

        String normalizedSearch = search == null ? "" : search.trim().toLowerCase();
        List<Map<String, Object>> allRows = teacherEmailsInDepartment.isEmpty()
            ? new ArrayList<>()
            : questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .filter(item -> item.getSourceTeacherEmail() != null)
                .filter(item -> teacherEmailsInDepartment.stream()
                    .anyMatch(email -> email.equalsIgnoreCase(item.getSourceTeacherEmail().trim())))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.getId());
                    row.put("subject", item.getSubject() == null ? "" : item.getSubject());
                    row.put("sourceExamName", item.getSourceExamName() == null ? "" : item.getSourceExamName());
                    row.put("difficulty", item.getDifficulty() == null ? "Medium" : item.getDifficulty());
                    row.put("sourceTeacherEmail", item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail());
                    String teacherEmail = item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail().trim().toLowerCase();
                    User teacherProfile = teacherProfilesByEmail.get(teacherEmail);
                    row.put("sourceTeacherDepartment", teacherProfile == null ? "" : (teacherProfile.getDepartmentName() == null ? "" : teacherProfile.getDepartmentName()));
                    row.put("sourceTeacherProgram", teacherProfile == null ? "" : (teacherProfile.getProgramName() == null ? "" : teacherProfile.getProgramName()));
                    row.put("questionPreview", toPreview(item.getQuestionText()));
                    row.put("questionText", item.getQuestionText() == null ? "" : item.getQuestionText());
                    row.put("createdAt", item.getCreatedAt());
                    return row;
                })
                .filter(row -> normalizedSearch.isBlank() || (
                    String.valueOf(row.getOrDefault("questionPreview", "")).toLowerCase().contains(normalizedSearch)
                        || String.valueOf(row.getOrDefault("questionText", "")).toLowerCase().contains(normalizedSearch)
                        || String.valueOf(row.getOrDefault("subject", "")).toLowerCase().contains(normalizedSearch)
                        || String.valueOf(row.getOrDefault("sourceTeacherEmail", "")).toLowerCase().contains(normalizedSearch)
                        || String.valueOf(row.getOrDefault("sourceExamName", "")).toLowerCase().contains(normalizedSearch)
                ))
                .toList();

        int safeSize = Math.max(5, Math.min(size, 50));
        int total = allRows.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * safeSize;
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> pagedRows = from < to ? allRows.subList(from, to) : new ArrayList<>();

        model.addAttribute("departmentName", departmentName.isBlank() ? "Department not set" : departmentName);
        model.addAttribute("questionBankRows", pagedRows);
        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("page", safePage);
        model.addAttribute("size", safeSize);
        model.addAttribute("total", total);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("hasPrev", safePage > 0);
        model.addAttribute("hasNext", safePage + 1 < totalPages);
        return "department-admin-question-bank";
    }

    private String toPreview(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return "-";
        }
        String flattened = questionText
            .replaceAll("<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (flattened.length() <= 140) {
            return flattened;
        }
        return flattened.substring(0, 137) + "...";
    }

    private String[] parseCsvRow(String line) {
        List<String> columns = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (character == ',' && !inQuotes) {
                columns.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        columns.add(current.toString().trim());
        return columns.toArray(new String[0]);
    }
}
