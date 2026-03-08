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
import com.exam.entity.EnrolledStudent;
import com.exam.entity.QuestionBankItem;
import com.exam.entity.Subject;
import com.exam.entity.User;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.QuestionBankItemRepository;
import com.exam.repository.SubjectRepository;
import com.exam.repository.UserRepository;

@Controller
@RequestMapping("/department-admin")
public class DepartmentAdminController {

    private static final String DEFAULT_IMPORTED_STUDENT_PASSWORD = "Student123!";

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;
    private final EnrolledStudentRepository enrolledStudentRepository;
    private final QuestionBankItemRepository questionBankItemRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DepartmentAdminController(UserRepository userRepository,
                                     SubjectRepository subjectRepository,
                                     EnrolledStudentRepository enrolledStudentRepository,
                                     QuestionBankItemRepository questionBankItemRepository,
                                     BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.enrolledStudentRepository = enrolledStudentRepository;
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

        List<User> allTeachers = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
            .toList();

        List<String> teacherEmailsInDepartment = allTeachers.stream()
            .filter(user -> departmentName.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
            .map(User::getEmail)
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
        for (User teacher : allTeachers) {
            if (teacher != null && teacher.getEmail() != null) {
                teacherProfilesByEmail.put(teacher.getEmail().trim().toLowerCase(), teacher);
            }
        }

        Map<String, List<String>> teacherEmailsByDepartment = new LinkedHashMap<>();
        for (String option : AcademicCatalog.DEPARTMENTS) {
            String normalizedOption = option == null ? "" : option.trim();
            List<String> emails = allTeachers.stream()
                .filter(user -> normalizedOption.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
                .map(User::getEmail)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .distinct()
                .toList();
            teacherEmailsByDepartment.put(normalizedOption, emails);
        }

        List<EnrolledStudent> allEnrollments = enrolledStudentRepository.findAll();
        Map<String, Long> studentCountByDepartment = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : teacherEmailsByDepartment.entrySet()) {
            Set<String> teacherEmailSet = entry.getValue().stream().collect(Collectors.toSet());
            long studentCount = allEnrollments.stream()
                .filter(item -> item != null && item.getTeacherEmail() != null && !item.getTeacherEmail().isBlank())
                .filter(item -> teacherEmailSet.contains(item.getTeacherEmail().trim().toLowerCase()))
                .map(EnrolledStudent::getStudentEmail)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .distinct()
                .count();
            studentCountByDepartment.put(entry.getKey(), studentCount);
        }

        Map<String, Long> teacherCountByDepartment = teacherEmailsByDepartment.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> (long) entry.getValue().size(),
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<QuestionBankItem> departmentQuestionBank = questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
            .filter(item -> sameDepartment(resolveItemDepartment(item, teacherProfilesByEmail), departmentName))
            .toList();

        Map<String, Long> questionCountByDepartment = new LinkedHashMap<>();
        for (String option : AcademicCatalog.DEPARTMENTS) {
            questionCountByDepartment.put(option, 0L);
        }

        for (QuestionBankItem item : questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if (item == null) {
                continue;
            }

            String itemDepartment = item.getSourceTeacherDepartment() == null ? "" : item.getSourceTeacherDepartment().trim();
            if (itemDepartment.isBlank() && item.getSourceTeacherEmail() != null) {
                User teacher = teacherProfilesByEmail.get(item.getSourceTeacherEmail().trim().toLowerCase());
                itemDepartment = teacher == null || teacher.getDepartmentName() == null ? "" : teacher.getDepartmentName().trim();
            }

            if (itemDepartment.isBlank() || !questionCountByDepartment.containsKey(itemDepartment)) {
                continue;
            }
            questionCountByDepartment.put(itemDepartment, questionCountByDepartment.get(itemDepartment) + 1);
        }

        Map<String, Long> subjectCountsByTeacher = departmentSubjects.stream()
            .filter(subject -> subject.getTeacherEmail() != null)
            .collect(Collectors.groupingBy(subject -> subject.getTeacherEmail().trim().toLowerCase(), Collectors.counting()));

        Map<String, Long> studentCountsByTeacher = allEnrollments.stream()
            .filter(item -> item != null && item.getTeacherEmail() != null && !item.getTeacherEmail().isBlank())
            .filter(item -> item.getStudentEmail() != null && !item.getStudentEmail().isBlank())
            .collect(Collectors.groupingBy(
                item -> item.getTeacherEmail().trim().toLowerCase(),
                Collectors.mapping(
                    item -> item.getStudentEmail().trim().toLowerCase(),
                    Collectors.collectingAndThen(Collectors.toSet(), set -> (long) set.size())
                )
            ));

        List<Map<String, Object>> departmentCards = new ArrayList<>();
        for (String option : AcademicCatalog.DEPARTMENTS) {
            List<String> departmentPrograms = AcademicCatalog.programsForDepartment(option);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("departmentName", option);
            card.put("teacherCount", teacherCountByDepartment.getOrDefault(option, 0L));
            card.put("studentCount", studentCountByDepartment.getOrDefault(option, 0L));
            card.put("questionCount", questionCountByDepartment.getOrDefault(option, 0L));
            card.put("programCount", departmentPrograms.size());
            card.put("programs", departmentPrograms);
            departmentCards.add(card);
        }

        model.addAttribute("departmentName", departmentName.isBlank() ? "Department not set" : departmentName);
        model.addAttribute("departmentTeacherCount", teacherEmailsInDepartment.size());
        model.addAttribute("departmentSubjectCount", departmentSubjects.size());
        model.addAttribute("departmentQuestionCount", departmentQuestionBank.size());
        model.addAttribute("departmentSubjects", departmentSubjects);
        model.addAttribute("teacherProfilesByEmail", teacherProfilesByEmail);
        model.addAttribute("subjectCountsByTeacher", subjectCountsByTeacher);
        model.addAttribute("studentCountsByTeacher", studentCountsByTeacher);
        model.addAttribute("departmentCards", departmentCards);
        model.addAttribute("departmentOptions", AcademicCatalog.DEPARTMENTS);
        model.addAttribute("programOptionsByDepartment", AcademicCatalog.PROGRAMS_BY_DEPARTMENT);
        model.addAttribute("currentUserDepartment", departmentName);
        return "department-admin-dashboard";
    }

    @PostMapping("/import-students")
    public String importStudents(@RequestParam("studentListFile") MultipartFile studentListFile,
                                 @RequestParam(name = "departmentName", required = false) String departmentName,
                                 @RequestParam(name = "programName", required = false) String programName,
                                 Principal principal,
                                 RedirectAttributes redirectAttributes) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Department Admin can import students here.");
            return "redirect:/department-admin/dashboard";
        }

        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        String adminDepartment = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        if (AcademicCatalog.isValidDepartment(adminDepartment)
            && !adminDepartment.equalsIgnoreCase(normalizedDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can import students only for your assigned department.");
            return "redirect:/department-admin/dashboard";
        }

        if (normalizedDepartment.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a department.");
            return "redirect:/department-admin/dashboard";
        }

        if (!AcademicCatalog.isValidDepartment(normalizedDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid department.");
            return "redirect:/department-admin/dashboard";
        }

        String normalizedProgram = programName == null ? "" : programName.trim();
        if (!AcademicCatalog.isValidProgram(normalizedDepartment, normalizedProgram)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid program for the selected department.");
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
                    student.setDepartmentName(normalizedDepartment);
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
                    if (!normalizedDepartment.equals(student.getDepartmentName())) {
                        student.setDepartmentName(normalizedDepartment);
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
                               @RequestParam(name = "departmentName", required = false) String departmentName,
                               @RequestParam(name = "subject", required = false) String subject,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "15") int size,
                               Model model,
                               Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        String adminDepartment = currentAdmin == null || currentAdmin.getDepartmentName() == null
            ? ""
            : currentAdmin.getDepartmentName().trim();

        final String selectedDepartment = AcademicCatalog.isValidDepartment(adminDepartment)
            ? adminDepartment
            : "";
        final String selectedSubject = subject == null ? "" : subject.trim();

        List<String> teacherEmailsInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> selectedDepartment.isBlank()
                || selectedDepartment.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
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
        List<Map<String, Object>> allRows = selectedDepartment.isBlank()
            ? new ArrayList<>()
            : questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .filter(item -> sameDepartment(resolveItemDepartment(item, teacherProfilesByEmail), selectedDepartment))
                .map(item -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.getId());
                    row.put("subject", item.getSubject() == null ? "" : item.getSubject());
                    row.put("sourceExamName", item.getSourceExamName() == null ? "" : item.getSourceExamName());
                    row.put("difficulty", item.getDifficulty() == null ? "Medium" : item.getDifficulty());
                    row.put("sourceTeacherEmail", item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail());
                    String teacherEmail = item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail().trim().toLowerCase();
                    User teacherProfile = teacherProfilesByEmail.get(teacherEmail);
                    String itemDepartment = resolveItemDepartment(item, teacherProfilesByEmail);
                    String rawProgram = teacherProfile == null ? "" : (teacherProfile.getProgramName() == null ? "" : teacherProfile.getProgramName().trim());
                    String resolvedProgram = AcademicCatalog.isValidProgram(itemDepartment, rawProgram) ? rawProgram : "";
                    row.put("sourceTeacherDepartment", itemDepartment);
                    row.put("sourceTeacherProgram", resolvedProgram);
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
                .filter(row -> selectedSubject.isBlank() || selectedSubject.equalsIgnoreCase(String.valueOf(row.getOrDefault("subject", "")).trim()))
                .toList();

        int safeSize = Math.max(5, Math.min(size, 50));
        int total = allRows.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * safeSize;
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> pagedRows = from < to ? allRows.subList(from, to) : new ArrayList<>();

        model.addAttribute("departmentName", selectedDepartment.isBlank() ? "Department not set" : selectedDepartment);
        model.addAttribute("selectedDepartment", selectedDepartment);
        model.addAttribute("selectedSubject", selectedSubject);
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

    @GetMapping("/department-view")
    public String departmentView(@RequestParam(name = "departmentName", required = false) String departmentName,
                                 Model model,
                                 Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            return "redirect:/login";
        }

        String adminDepartment = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        String requestedDepartment = departmentName == null ? "" : departmentName.trim();
        String selectedDepartment = AcademicCatalog.isValidDepartment(requestedDepartment)
            ? requestedDepartment
            : adminDepartment;

        List<User> teachersInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> sameDepartment(user.getDepartmentName(), selectedDepartment))
            .sorted(Comparator.comparing(
                user -> user.getFullName() == null || user.getFullName().isBlank() ? user.getEmail() : user.getFullName(),
                String.CASE_INSENSITIVE_ORDER
            ))
            .toList();

        Set<String> teacherEmailSet = teachersInDepartment.stream()
            .map(User::getEmail)
            .filter(email -> email != null && !email.isBlank())
            .map(this::normalizeEmail)
            .collect(Collectors.toSet());

        List<Subject> subjectsInDepartment = teacherEmailSet.isEmpty()
            ? new ArrayList<>()
            : subjectRepository.findAll().stream()
                .filter(subject -> subject != null && subject.getTeacherEmail() != null)
                .filter(subject -> teacherEmailSet.contains(normalizeEmail(subject.getTeacherEmail())))
                .toList();

        Map<String, Long> subjectCountsByTeacher = subjectsInDepartment.stream()
            .filter(subject -> subject.getTeacherEmail() != null)
            .collect(Collectors.groupingBy(
                subject -> normalizeEmail(subject.getTeacherEmail()),
                Collectors.counting()
            ));

        List<EnrolledStudent> enrollmentsInDepartment = teacherEmailSet.isEmpty()
            ? new ArrayList<>()
            : enrolledStudentRepository.findAll().stream()
                .filter(enrollment -> enrollment != null && enrollment.getTeacherEmail() != null)
                .filter(enrollment -> teacherEmailSet.contains(normalizeEmail(enrollment.getTeacherEmail())))
                .toList();

        Map<String, User> studentProfilesByEmail = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.STUDENT)
            .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
            .collect(Collectors.toMap(
                user -> normalizeEmail(user.getEmail()),
                user -> user,
                (left, right) -> left
            ));

        Map<String, List<EnrolledStudent>> enrollmentsByTeacher = enrollmentsInDepartment.stream()
            .collect(Collectors.groupingBy(
                enrollment -> normalizeEmail(enrollment.getTeacherEmail()),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        List<Map<String, Object>> teacherRows = new ArrayList<>();
        for (User teacher : teachersInDepartment) {
            String teacherEmailKey = normalizeEmail(teacher.getEmail());
            List<EnrolledStudent> teacherEnrollments = enrollmentsByTeacher.getOrDefault(teacherEmailKey, new ArrayList<>());

            List<Map<String, Object>> studentRows = teacherEnrollments.stream()
                .sorted(Comparator.comparing(
                    enrollment -> enrollment.getStudentName() == null ? "" : enrollment.getStudentName(),
                    String.CASE_INSENSITIVE_ORDER
                ))
                .map(enrollment -> {
                    Map<String, Object> studentRow = new LinkedHashMap<>();
                    String studentEmail = enrollment.getStudentEmail() == null ? "" : enrollment.getStudentEmail().trim();
                    User studentProfile = studentProfilesByEmail.get(normalizeEmail(studentEmail));
                    String profileName = studentProfile == null ? "" : (studentProfile.getFullName() == null ? "" : studentProfile.getFullName().trim());
                    String profileProgram = studentProfile == null ? "" : (studentProfile.getProgramName() == null ? "" : studentProfile.getProgramName().trim());

                    studentRow.put("studentName", !profileName.isBlank() ? profileName : enrollment.getStudentName());
                    studentRow.put("studentEmail", studentEmail);
                    studentRow.put("programName", profileProgram.isBlank() ? "-" : profileProgram);
                    studentRow.put("subjectName", enrollment.getSubjectName() == null || enrollment.getSubjectName().isBlank()
                        ? "-"
                        : enrollment.getSubjectName().trim());
                    studentRow.put("enrolledAt", enrollment.getEnrolledAt());
                    return studentRow;
                })
                .toList();

            long uniqueStudentCount = teacherEnrollments.stream()
                .map(EnrolledStudent::getStudentEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(this::normalizeEmail)
                .distinct()
                .count();

            Map<String, Object> teacherRow = new LinkedHashMap<>();
            teacherRow.put("teacher", teacher);
            teacherRow.put("subjectCount", subjectCountsByTeacher.getOrDefault(teacherEmailKey, 0L));
            teacherRow.put("studentCount", uniqueStudentCount);
            teacherRow.put("studentRows", studentRows);
            teacherRows.add(teacherRow);
        }

        long totalUniqueStudents = enrollmentsInDepartment.stream()
            .map(EnrolledStudent::getStudentEmail)
            .filter(email -> email != null && !email.isBlank())
            .map(this::normalizeEmail)
            .distinct()
            .count();

        model.addAttribute("selectedDepartment", selectedDepartment);
        model.addAttribute("departmentOptions", AcademicCatalog.DEPARTMENTS);
        model.addAttribute("teacherRows", teacherRows);
        model.addAttribute("teacherCount", teachersInDepartment.size());
        model.addAttribute("subjectCount", subjectsInDepartment.size());
        model.addAttribute("studentCount", totalUniqueStudents);
        return "department-admin-department-view";
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

    private String resolveItemDepartment(QuestionBankItem item, Map<String, User> teacherProfilesByEmail) {
        if (item == null) {
            return "";
        }

        String department = item.getSourceTeacherDepartment() == null ? "" : item.getSourceTeacherDepartment().trim();
        if (!department.isBlank()) {
            return department;
        }

        if (item.getSourceTeacherEmail() == null || item.getSourceTeacherEmail().isBlank()) {
            return "";
        }

        User teacherProfile = teacherProfilesByEmail.get(item.getSourceTeacherEmail().trim().toLowerCase());
        if (teacherProfile == null || teacherProfile.getDepartmentName() == null) {
            return "";
        }

        return teacherProfile.getDepartmentName().trim();
    }

    private boolean sameDepartment(String left, String right) {
        String leftValue = left == null ? "" : left.trim();
        String rightValue = right == null ? "" : right.trim();
        if (leftValue.isBlank() || rightValue.isBlank()) {
            return false;
        }
        return leftValue.equalsIgnoreCase(rightValue);
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
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
