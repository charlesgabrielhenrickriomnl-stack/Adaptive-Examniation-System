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

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.exam.config.AcademicCatalog;
import com.exam.entity.DepartmentProgram;
import com.exam.entity.EnrolledStudent;
import com.exam.entity.QuestionBankItem;
import com.exam.entity.Subject;
import com.exam.entity.User;
import com.exam.repository.DepartmentProgramRepository;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.QuestionBankItemRepository;
import com.exam.repository.SubjectRepository;
import com.exam.repository.UserRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Controller
@RequestMapping("/department-admin")
public class DepartmentAdminController {

    private static final String DEFAULT_IMPORTED_STUDENT_PASSWORD = "Student123!";

    private final UserRepository userRepository;
    private final SubjectRepository subjectRepository;
    private final EnrolledStudentRepository enrolledStudentRepository;
    private final DepartmentProgramRepository departmentProgramRepository;
    private final QuestionBankItemRepository questionBankItemRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Gson gson = new Gson();

    public DepartmentAdminController(UserRepository userRepository,
                                     SubjectRepository subjectRepository,
                                     EnrolledStudentRepository enrolledStudentRepository,
                                     DepartmentProgramRepository departmentProgramRepository,
                                     QuestionBankItemRepository questionBankItemRepository,
                                     BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.enrolledStudentRepository = enrolledStudentRepository;
        this.departmentProgramRepository = departmentProgramRepository;
        this.questionBankItemRepository = questionBankItemRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            return "redirect:/login";
        }

        String departmentName = currentAdmin.getDepartmentName() == null
            ? ""
            : currentAdmin.getDepartmentName().trim();

        List<User> teachersInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
            .filter(user -> sameDepartment(user.getDepartmentName(), departmentName))
            .toList();

        Set<String> teacherEmailsInDepartment = teachersInDepartment.stream()
            .map(User::getEmail)
            .filter(value -> value != null && !value.isBlank())
            .map(this::normalizeEmail)
            .collect(Collectors.toSet());

        List<Subject> departmentSubjects = teacherEmailsInDepartment.isEmpty()
            ? new ArrayList<>()
            : subjectRepository.findAll().stream()
                .filter(subject -> subject != null && subject.getTeacherEmail() != null)
                .filter(subject -> teacherEmailsInDepartment.contains(normalizeEmail(subject.getTeacherEmail())))
                .sorted(Comparator.comparing(Subject::getSubjectName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        Map<String, User> teacherProfilesByEmail = new HashMap<>();
        for (User teacher : teachersInDepartment) {
            if (teacher != null && teacher.getEmail() != null) {
                teacherProfilesByEmail.put(teacher.getEmail().trim().toLowerCase(), teacher);
            }
        }

        List<EnrolledStudent> allEnrollments = enrolledStudentRepository.findAll();
        List<EnrolledStudent> departmentEnrollments = allEnrollments.stream()
            .filter(item -> item != null && item.getTeacherEmail() != null && !item.getTeacherEmail().isBlank())
            .filter(item -> teacherEmailsInDepartment.contains(normalizeEmail(item.getTeacherEmail())))
            .toList();

        List<QuestionBankItem> departmentQuestionBank = questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
            .filter(item -> sameDepartment(resolveItemDepartment(item, teacherProfilesByEmail), departmentName))
            .toList();

        Map<String, Long> subjectCountsByTeacher = departmentSubjects.stream()
            .filter(subject -> subject.getTeacherEmail() != null)
            .collect(Collectors.groupingBy(subject -> subject.getTeacherEmail().trim().toLowerCase(), Collectors.counting()));

        Map<String, Long> studentCountsByTeacher = departmentEnrollments.stream()
            .filter(item -> item != null && item.getTeacherEmail() != null && !item.getTeacherEmail().isBlank())
            .filter(item -> item.getStudentEmail() != null && !item.getStudentEmail().isBlank())
            .collect(Collectors.groupingBy(
                item -> item.getTeacherEmail().trim().toLowerCase(),
                Collectors.mapping(
                    item -> item.getStudentEmail().trim().toLowerCase(),
                    Collectors.collectingAndThen(Collectors.toSet(), set -> (long) set.size())
                )
            ));

        List<String> departmentPrograms = buildProgramOptionsForDepartment(departmentName, teachersInDepartment, currentAdmin);
        List<Map<String, Object>> programCards = new ArrayList<>();
        for (String programName : departmentPrograms) {
            List<User> programTeachers = teachersInDepartment.stream()
                .filter(teacher -> sameProgram(teacher.getProgramName(), programName))
                .sorted(Comparator.comparing(
                    teacher -> teacher.getFullName() == null || teacher.getFullName().isBlank() ? teacher.getEmail() : teacher.getFullName(),
                    String.CASE_INSENSITIVE_ORDER
                ))
                .toList();

            Set<String> programTeacherEmails = programTeachers.stream()
                .map(User::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(this::normalizeEmail)
                .collect(Collectors.toSet());

            long programSubjectCount = departmentSubjects.stream()
                .filter(subject -> subject.getTeacherEmail() != null)
                .map(subject -> normalizeEmail(subject.getTeacherEmail()))
                .filter(programTeacherEmails::contains)
                .count();

            long programStudentCount = departmentEnrollments.stream()
                .filter(item -> item.getTeacherEmail() != null)
                .filter(item -> programTeacherEmails.contains(normalizeEmail(item.getTeacherEmail())))
                .map(EnrolledStudent::getStudentEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(this::normalizeEmail)
                .distinct()
                .count();

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("programName", programName);
            card.put("programKey", toDomKey(programName));
            card.put("unassignedProgram", false);
            card.put("teacherCount", programTeachers.size());
            card.put("subjectCount", programSubjectCount);
            card.put("studentCount", programStudentCount);
            programCards.add(card);
        }

        List<User> unassignedProgramTeachers = teachersInDepartment.stream()
            .filter(teacher -> teacher.getProgramName() == null || teacher.getProgramName().isBlank())
            .toList();
        if (!unassignedProgramTeachers.isEmpty()) {
            Set<String> unassignedTeacherEmails = unassignedProgramTeachers.stream()
                .map(User::getEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(this::normalizeEmail)
                .collect(Collectors.toSet());

            long unassignedSubjectCount = departmentSubjects.stream()
                .filter(subject -> subject.getTeacherEmail() != null)
                .map(subject -> normalizeEmail(subject.getTeacherEmail()))
                .filter(unassignedTeacherEmails::contains)
                .count();

            long unassignedStudentCount = departmentEnrollments.stream()
                .filter(item -> item.getTeacherEmail() != null)
                .filter(item -> unassignedTeacherEmails.contains(normalizeEmail(item.getTeacherEmail())))
                .map(EnrolledStudent::getStudentEmail)
                .filter(email -> email != null && !email.isBlank())
                .map(this::normalizeEmail)
                .distinct()
                .count();

            Map<String, Object> unassignedCard = new LinkedHashMap<>();
            unassignedCard.put("programName", "Unassigned Program");
            unassignedCard.put("programKey", "unassigned-program");
            unassignedCard.put("unassignedProgram", true);
            unassignedCard.put("teacherCount", unassignedProgramTeachers.size());
            unassignedCard.put("subjectCount", unassignedSubjectCount);
            unassignedCard.put("studentCount", unassignedStudentCount);
            programCards.add(unassignedCard);
        }

        model.addAttribute("departmentName", departmentName.isBlank() ? "Department not set" : departmentName);
        model.addAttribute("departmentTeacherCount", teachersInDepartment.size());
        model.addAttribute("departmentSubjectCount", departmentSubjects.size());
        model.addAttribute("departmentQuestionCount", departmentQuestionBank.size());
        model.addAttribute("departmentSubjects", departmentSubjects);
        model.addAttribute("teacherProfilesByEmail", teacherProfilesByEmail);
        model.addAttribute("subjectCountsByTeacher", subjectCountsByTeacher);
        model.addAttribute("studentCountsByTeacher", studentCountsByTeacher);
        model.addAttribute("programCards", programCards);
        model.addAttribute("departmentPrograms", departmentPrograms);
        model.addAttribute("currentUserDepartment", departmentName);
        return "department-admin-dashboard";
    }

    @PostMapping("/programs/create")
    public String createProgram(@RequestParam(name = "departmentName", required = false) String departmentName,
                                @RequestParam(name = "programName", required = false) String programName,
                                Principal principal,
                                RedirectAttributes redirectAttributes) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Department Admin can create programs.");
            return "redirect:/department-admin/dashboard";
        }

        String adminDepartment = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        String requestedDepartment = departmentName == null ? "" : departmentName.trim();
        if (!sameDepartment(adminDepartment, requestedDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can create programs only for your department.");
            return "redirect:/department-admin/dashboard";
        }

        String normalizedProgram = programName == null ? "" : programName.trim();
        if (normalizedProgram.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Program name is required.");
            return "redirect:/department-admin/dashboard";
        }

        if ("Unassigned Program".equalsIgnoreCase(normalizedProgram)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please choose a different program name.");
            return "redirect:/department-admin/dashboard";
        }

        List<User> teachersInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> sameDepartment(user.getDepartmentName(), adminDepartment))
            .toList();
        List<String> existingPrograms = buildProgramOptionsForDepartment(adminDepartment, teachersInDepartment, currentAdmin);
        boolean duplicate = existingPrograms.stream().anyMatch(program -> program.equalsIgnoreCase(normalizedProgram));
        if (duplicate) {
            redirectAttributes.addFlashAttribute("errorMessage", "Program already exists in your department.");
            return "redirect:/department-admin/dashboard";
        }

        DepartmentProgram departmentProgram = new DepartmentProgram();
        departmentProgram.setDepartmentName(adminDepartment);
        departmentProgram.setProgramName(normalizedProgram);
        departmentProgram.setCreatedByEmail(adminEmail);
        departmentProgramRepository.save(departmentProgram);

        redirectAttributes.addFlashAttribute("successMessage", "Program created: " + normalizedProgram);
        return "redirect:/department-admin/dashboard";
    }

    @PostMapping("/import-students")
    public String importStudents(@RequestParam("studentListFile") MultipartFile studentListFile,
                                 @RequestParam(name = "teacherEmail", required = false) String teacherEmail,
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

        String adminDepartment = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        String normalizedTeacherEmail = normalizeEmail(teacherEmail);
        User selectedTeacher = normalizedTeacherEmail.isBlank()
            ? null
            : userRepository.findByEmail(normalizedTeacherEmail).orElse(null);

        String requestedDepartment = departmentName == null ? "" : departmentName.trim();
        String requestedProgram = programName == null ? "" : programName.trim();
        String redirectDepartment = sameDepartment(requestedDepartment, adminDepartment) ? requestedDepartment : adminDepartment;
        String redirectTarget = buildProgramQuestionBankRedirect(redirectDepartment, requestedProgram);

        if (selectedTeacher == null || selectedTeacher.getRole() != User.Role.TEACHER) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid teacher.");
            return redirectTarget;
        }

        String teacherDepartment = selectedTeacher.getDepartmentName() == null ? "" : selectedTeacher.getDepartmentName().trim();
        if (!sameDepartment(teacherDepartment, adminDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can import students only for teachers in your department.");
            return redirectTarget;
        }

        String teacherProgram = selectedTeacher.getProgramName() == null ? "" : selectedTeacher.getProgramName().trim();
        if (teacherProgram.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Selected teacher must have a program before importing students.");
            return redirectTarget;
        }

        redirectTarget = buildProgramQuestionBankRedirect(teacherDepartment, teacherProgram);

        List<Subject> teacherSubjects = subjectRepository.findByTeacherEmail(selectedTeacher.getEmail()).stream()
            .filter(subject -> subject != null && subject.getId() != null)
            .sorted(Comparator.comparing(Subject::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        if (teacherSubjects.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Selected teacher must have at least one subject to enroll imported students.");
            return redirectTarget;
        }

        Subject targetSubject = teacherSubjects.get(0);
        String normalizedDepartment = teacherDepartment;
        String normalizedProgram = teacherProgram;
        String resolvedTeacherEmail = selectedTeacher.getEmail() == null ? normalizedTeacherEmail : selectedTeacher.getEmail().trim();

        if (studentListFile == null || studentListFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please upload a CSV file.");
            return redirectTarget;
        }

        int rowsRead = 0;
        int createdAccounts = 0;
        int updatedAccounts = 0;
        int enrolledCount = 0;
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

                if (enrolledStudentRepository.findByTeacherEmailAndStudentEmailAndSubjectId(resolvedTeacherEmail, rawEmail, targetSubject.getId()).isEmpty()) {
                    String studentName = rawName.isBlank() ? rawEmail : rawName;
                    EnrolledStudent enrollment = new EnrolledStudent(
                        resolvedTeacherEmail,
                        rawEmail,
                        studentName,
                        targetSubject.getId(),
                        targetSubject.getSubjectName()
                    );
                    enrolledStudentRepository.save(enrollment);
                    enrolledCount++;
                }
            }
        } catch (IOException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to read the uploaded CSV file.");
            return redirectTarget;
        }

        if (rowsRead == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No student rows found. Use CSV format: Full Name,Email,Password(optional).");
            return redirectTarget;
        }

        String summary = "Import complete: "
            + enrolledCount + " enrolled under " + resolvedTeacherEmail + ", "
            + createdAccounts + " account(s) created, "
            + updatedAccounts + " account(s) updated, "
            + skippedRows + " row(s) skipped.";
        redirectAttributes.addFlashAttribute("successMessage", summary);
        return redirectTarget;
    }

    @PostMapping("/import-teachers")
    public String importTeachers(@RequestParam("teacherListFile") MultipartFile teacherListFile,
                                 @RequestParam(name = "departmentName", required = false) String departmentName,
                                 @RequestParam(name = "programName", required = false) String programName,
                                 Principal principal,
                                 RedirectAttributes redirectAttributes) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Department Admin can import teachers here.");
            return "redirect:/department-admin/dashboard";
        }

        String adminDepartment = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        String normalizedProgram = programName == null ? "" : programName.trim();
        String redirectTarget = buildProgramQuestionBankRedirect(
            normalizedDepartment.isBlank() ? adminDepartment : normalizedDepartment,
            normalizedProgram
        );

        if (!sameDepartment(adminDepartment, normalizedDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can import teachers only for your department.");
            return redirectTarget;
        }

        if (programName == null || programName.trim().isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please enter a program for imported teachers.");
            return redirectTarget;
        }

        if (teacherListFile == null || teacherListFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please upload a CSV file.");
            return redirectTarget;
        }

        normalizedProgram = programName.trim();
        int rowsRead = 0;
        int createdAccounts = 0;
        int updatedAccounts = 0;
        int skippedRows = 0;
        Set<String> seenEmails = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(teacherListFile.getInputStream(), StandardCharsets.UTF_8))) {
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
                String rawEmail = columns[1] == null ? "" : normalizeEmail(columns[1]);
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

                User teacher = userRepository.findByEmail(rawEmail).orElse(null);
                if (teacher == null) {
                    teacher = new User();
                    teacher.setEmail(rawEmail);
                    teacher.setPassword(passwordEncoder.encode(effectivePassword));
                    teacher.setFullName(effectiveName);
                    teacher.setSchoolName(AcademicCatalog.SCHOOL_NAME);
                    teacher.setCampusName(AcademicCatalog.CAMPUS_NAME);
                    teacher.setDepartmentName(normalizedDepartment);
                    teacher.setProgramName(normalizedProgram);
                    teacher.setRole(User.Role.TEACHER);
                    teacher.setEnabled(true);
                    teacher.setVerificationToken(null);
                    userRepository.save(teacher);
                    createdAccounts++;
                } else if (teacher.getRole() != User.Role.TEACHER) {
                    skippedRows++;
                } else {
                    boolean changed = false;
                    if (!effectiveName.equals(teacher.getFullName())) {
                        teacher.setFullName(effectiveName);
                        changed = true;
                    }
                    if (!AcademicCatalog.SCHOOL_NAME.equals(teacher.getSchoolName())) {
                        teacher.setSchoolName(AcademicCatalog.SCHOOL_NAME);
                        changed = true;
                    }
                    if (!AcademicCatalog.CAMPUS_NAME.equals(teacher.getCampusName())) {
                        teacher.setCampusName(AcademicCatalog.CAMPUS_NAME);
                        changed = true;
                    }
                    if (!normalizedDepartment.equals(teacher.getDepartmentName())) {
                        teacher.setDepartmentName(normalizedDepartment);
                        changed = true;
                    }
                    if (!normalizedProgram.equals(teacher.getProgramName() == null ? "" : teacher.getProgramName())) {
                        teacher.setProgramName(normalizedProgram);
                        changed = true;
                    }
                    if (!teacher.isEnabled()) {
                        teacher.setEnabled(true);
                        changed = true;
                    }
                    if (!rawPassword.isBlank()) {
                        teacher.setPassword(passwordEncoder.encode(rawPassword));
                        changed = true;
                    }

                    if (changed) {
                        userRepository.save(teacher);
                        updatedAccounts++;
                    }
                }
            }
        } catch (IOException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to read the uploaded CSV file.");
            return redirectTarget;
        }

        if (rowsRead == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No teacher rows found. Use CSV format: Full Name,Email,Password(optional).");
            return redirectTarget;
        }

        String summary = "Teacher import complete: "
            + createdAccounts + " account(s) created, "
            + updatedAccounts + " account(s) updated, "
            + skippedRows + " row(s) skipped.";
        redirectAttributes.addFlashAttribute("successMessage", summary);
        return redirectTarget;
    }

    @GetMapping("/question-bank")
    public String questionBank(@RequestParam(name = "search", required = false) String search,
                               @RequestParam(name = "departmentName", required = false) String departmentName,
                               @RequestParam(name = "programName", required = false) String programName,
                               @RequestParam(name = "sourceExamId", required = false) String sourceExamId,
                               @RequestParam(name = "sourceExamName", required = false) String sourceExamName,
                               @RequestParam(name = "subject", required = false) String subject,
                               @RequestParam(name = "page", defaultValue = "0") int page,
                               @RequestParam(name = "size", defaultValue = "15") int size,
                               Model model,
                               Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            return "redirect:/login";
        }

        String adminDepartment = currentAdmin.getDepartmentName() == null
            ? ""
            : currentAdmin.getDepartmentName().trim();

        String requestedDepartment = departmentName == null ? "" : departmentName.trim();
        final String selectedDepartment = sameDepartment(requestedDepartment, adminDepartment)
            ? requestedDepartment
            : adminDepartment;
        String requestedProgram = programName == null ? "" : programName.trim();
        boolean unassignedProgramFilter = "Unassigned Program".equalsIgnoreCase(requestedProgram);
        final String selectedProgram = unassignedProgramFilter ? "Unassigned Program" : requestedProgram;
        final String selectedSubject = subject == null ? "" : subject.trim();

        List<User> teachersInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> selectedDepartment.isBlank()
                || selectedDepartment.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
            .filter(user -> {
                if (selectedProgram.isBlank()) {
                    return true;
                }

                String teacherProgram = user.getProgramName() == null ? "" : user.getProgramName().trim();
                if (unassignedProgramFilter) {
                    return teacherProgram.isBlank();
                }
                return sameProgram(teacherProgram, selectedProgram);
            })
            .toList();

        Set<String> teacherEmailSetInScope = teachersInDepartment.stream()
            .map(User::getEmail)
            .filter(value -> value != null && !value.isBlank())
            .map(this::normalizeEmail)
            .collect(Collectors.toSet());

        Map<String, User> teacherProfilesByEmail = new HashMap<>();
        for (User teacher : teachersInDepartment) {
            if (teacher != null && teacher.getEmail() != null) {
                teacherProfilesByEmail.put(teacher.getEmail().trim().toLowerCase(), teacher);
            }
        }

        List<QuestionBankItem> scopedItems = selectedDepartment.isBlank()
            ? new ArrayList<>()
            : questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .filter(item -> sameDepartment(resolveItemDepartment(item, teacherProfilesByEmail), selectedDepartment))
                .filter(item -> {
                    if (selectedProgram.isBlank()) {
                        return true;
                    }
                    if (item == null || item.getSourceTeacherEmail() == null || item.getSourceTeacherEmail().isBlank()) {
                        return false;
                    }
                    return teacherEmailSetInScope.contains(normalizeEmail(item.getSourceTeacherEmail()));
                })
                .filter(item -> selectedSubject.isBlank() || selectedSubject.equalsIgnoreCase(item.getSubject() == null ? "" : item.getSubject().trim()))
                .toList();

        Map<String, Map<String, Object>> quizRowsByKey = new LinkedHashMap<>();
        for (QuestionBankItem item : scopedItems) {
            if (item == null) {
                continue;
            }

            String sourceTeacherEmail = item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail().trim();
            String normalizedTeacherEmail = normalizeEmail(sourceTeacherEmail);
            User teacherProfile = teacherProfilesByEmail.get(normalizedTeacherEmail);
            String resolvedDepartment = resolveItemDepartment(item, teacherProfilesByEmail);
            String resolvedProgram = teacherProfile == null || teacherProfile.getProgramName() == null
                ? ""
                : teacherProfile.getProgramName().trim();
            String resolvedExamId = item.getSourceExamId() == null ? "" : item.getSourceExamId().trim();
            String resolvedExamName = item.getSourceExamName() == null ? "" : item.getSourceExamName().trim();
            if (resolvedExamName.isBlank()) {
                resolvedExamName = "Untitled Quiz";
            }

            String quizKey = !resolvedExamId.isBlank()
                ? ("id::" + resolvedExamId.toLowerCase())
                : ("name::" + resolvedExamName.toLowerCase() + "::" + normalizedTeacherEmail);

            Map<String, Object> quizRow = quizRowsByKey.get(quizKey);
            if (quizRow == null) {
                quizRow = new LinkedHashMap<>();
                quizRow.put("sourceExamId", resolvedExamId);
                quizRow.put("sourceExamName", resolvedExamName);
                quizRow.put("subject", item.getSubject() == null ? "" : item.getSubject().trim());
                quizRow.put("sourceTeacherEmail", sourceTeacherEmail);
                quizRow.put("sourceTeacherDepartment", resolvedDepartment);
                quizRow.put("sourceTeacherProgram", resolvedProgram);
                quizRow.put("questionCount", 0);
                quizRow.put("createdAt", item.getCreatedAt());
                quizRowsByKey.put(quizKey, quizRow);
            }

            int questionCount = (Integer) quizRow.getOrDefault("questionCount", 0);
            quizRow.put("questionCount", questionCount + 1);
        }

        String normalizedSearch = search == null ? "" : search.trim().toLowerCase();
        List<Map<String, Object>> allQuizRows = quizRowsByKey.values().stream()
            .filter(row -> normalizedSearch.isBlank() || (
                String.valueOf(row.getOrDefault("sourceExamName", "")).toLowerCase().contains(normalizedSearch)
                    || String.valueOf(row.getOrDefault("subject", "")).toLowerCase().contains(normalizedSearch)
                    || String.valueOf(row.getOrDefault("sourceTeacherEmail", "")).toLowerCase().contains(normalizedSearch)
                    || String.valueOf(row.getOrDefault("sourceTeacherDepartment", "")).toLowerCase().contains(normalizedSearch)
                    || String.valueOf(row.getOrDefault("sourceTeacherProgram", "")).toLowerCase().contains(normalizedSearch)
            ))
            .toList();

        int safeSize = Math.max(5, Math.min(size, 50));
        int total = allQuizRows.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) safeSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * safeSize;
        int to = Math.min(from + safeSize, total);
        List<Map<String, Object>> pagedQuizRows = from < to ? allQuizRows.subList(from, to) : new ArrayList<>();

        model.addAttribute("departmentName", selectedDepartment.isBlank() ? "Department not set" : selectedDepartment);
        model.addAttribute("selectedDepartment", selectedDepartment);
        model.addAttribute("selectedProgram", selectedProgram);
        model.addAttribute("selectedSubject", selectedSubject);
        model.addAttribute("quizRows", pagedQuizRows);
        List<User> teacherOptions = teachersInDepartment.stream()
            .sorted(Comparator.comparing(
                teacher -> teacher.getFullName() == null || teacher.getFullName().isBlank() ? teacher.getEmail() : teacher.getFullName(),
                String.CASE_INSENSITIVE_ORDER
            ))
            .toList();
        boolean canManageImports = !selectedProgram.isBlank() && !"Unassigned Program".equalsIgnoreCase(selectedProgram);
        model.addAttribute("teacherOptions", canManageImports ? teacherOptions : new ArrayList<>());
        model.addAttribute("canManageImports", canManageImports);
        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("page", safePage);
        model.addAttribute("size", safeSize);
        model.addAttribute("total", total);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("hasPrev", safePage > 0);
        model.addAttribute("hasNext", safePage + 1 < totalPages);
        return "department-admin-question-bank";
    }

    @GetMapping("/question-bank/quiz")
    public String questionBankQuizDetail(@RequestParam(name = "departmentName", required = false) String departmentName,
                                         @RequestParam(name = "programName", required = false) String programName,
                                         @RequestParam(name = "sourceExamId", required = false) String sourceExamId,
                                         @RequestParam(name = "sourceExamName", required = false) String sourceExamName,
                                         @RequestParam(name = "subject", required = false) String subject,
                                         @RequestParam(name = "search", required = false) String search,
                                         @RequestParam(name = "page", defaultValue = "0") int page,
                                         @RequestParam(name = "size", defaultValue = "15") int size,
                                         Model model,
                                         Principal principal) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            return "redirect:/login";
        }

        String selectedExamId = sourceExamId == null ? "" : sourceExamId.trim();
        String selectedExamName = sourceExamName == null ? "" : sourceExamName.trim();
        if (selectedExamId.isBlank() && selectedExamName.isBlank()) {
            return "redirect:/department-admin/question-bank";
        }

        String adminDepartment = currentAdmin.getDepartmentName() == null
            ? ""
            : currentAdmin.getDepartmentName().trim();
        String requestedDepartment = departmentName == null ? "" : departmentName.trim();
        final String selectedDepartment = sameDepartment(requestedDepartment, adminDepartment)
            ? requestedDepartment
            : adminDepartment;
        String requestedProgram = programName == null ? "" : programName.trim();
        boolean unassignedProgramFilter = "Unassigned Program".equalsIgnoreCase(requestedProgram);
        final String selectedProgram = unassignedProgramFilter ? "Unassigned Program" : requestedProgram;
        final String selectedSubject = subject == null ? "" : subject.trim();

        List<User> teachersInDepartment = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> selectedDepartment.isBlank()
                || selectedDepartment.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
            .filter(user -> {
                if (selectedProgram.isBlank()) {
                    return true;
                }

                String teacherProgram = user.getProgramName() == null ? "" : user.getProgramName().trim();
                if (unassignedProgramFilter) {
                    return teacherProgram.isBlank();
                }
                return sameProgram(teacherProgram, selectedProgram);
            })
            .toList();

        Set<String> teacherEmailSetInScope = teachersInDepartment.stream()
            .map(User::getEmail)
            .filter(value -> value != null && !value.isBlank())
            .map(this::normalizeEmail)
            .collect(Collectors.toSet());

        Map<String, User> teacherProfilesByEmail = new HashMap<>();
        for (User teacher : teachersInDepartment) {
            if (teacher != null && teacher.getEmail() != null) {
                teacherProfilesByEmail.put(teacher.getEmail().trim().toLowerCase(), teacher);
            }
        }

        List<QuestionBankItem> selectedQuizItems = selectedDepartment.isBlank()
            ? new ArrayList<>()
            : questionBankItemRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .filter(item -> sameDepartment(resolveItemDepartment(item, teacherProfilesByEmail), selectedDepartment))
                .filter(item -> {
                    if (selectedProgram.isBlank()) {
                        return true;
                    }
                    if (item == null || item.getSourceTeacherEmail() == null || item.getSourceTeacherEmail().isBlank()) {
                        return false;
                    }
                    return teacherEmailSetInScope.contains(normalizeEmail(item.getSourceTeacherEmail()));
                })
                .filter(item -> selectedSubject.isBlank() || selectedSubject.equalsIgnoreCase(item.getSubject() == null ? "" : item.getSubject().trim()))
                .filter(item -> {
                    String itemExamId = item.getSourceExamId() == null ? "" : item.getSourceExamId().trim();
                    String itemExamName = item.getSourceExamName() == null ? "" : item.getSourceExamName().trim();
                    if (!selectedExamId.isBlank()) {
                        return itemExamId.equalsIgnoreCase(selectedExamId);
                    }
                    return itemExamName.equalsIgnoreCase(selectedExamName);
                })
                .sorted(Comparator.comparing(item -> {
                    Integer questionOrder = item.getQuestionOrder();
                    return questionOrder == null ? Integer.MAX_VALUE : questionOrder;
                }))
                .toList();

        Map<String, Object> selectedQuiz = null;
        List<Map<String, Object>> selectedQuizQuestions = new ArrayList<>();
        if (!selectedQuizItems.isEmpty()) {
            QuestionBankItem firstItem = selectedQuizItems.get(0);
            String firstExamId = firstItem.getSourceExamId() == null ? "" : firstItem.getSourceExamId().trim();
            String firstExamName = firstItem.getSourceExamName() == null ? "" : firstItem.getSourceExamName().trim();

            String teacherEmail = firstItem.getSourceTeacherEmail() == null ? "" : firstItem.getSourceTeacherEmail().trim();
            User teacherProfile = teacherProfilesByEmail.get(normalizeEmail(teacherEmail));

            selectedQuiz = new LinkedHashMap<>();
            selectedQuiz.put("sourceExamId", firstExamId);
            selectedQuiz.put("sourceExamName", firstExamName.isBlank() ? "Untitled Quiz" : firstExamName);
            selectedQuiz.put("subject", firstItem.getSubject() == null ? "" : firstItem.getSubject().trim());
            selectedQuiz.put("sourceTeacherEmail", teacherEmail);
            selectedQuiz.put("sourceTeacherDepartment", resolveItemDepartment(firstItem, teacherProfilesByEmail));
            selectedQuiz.put("sourceTeacherProgram", teacherProfile == null || teacherProfile.getProgramName() == null
                ? ""
                : teacherProfile.getProgramName().trim());
            selectedQuiz.put("questionCount", selectedQuizItems.size());

            int index = 1;
            for (QuestionBankItem item : selectedQuizItems) {
                Map<String, Object> questionRow = new LinkedHashMap<>();
                Integer questionOrder = item.getQuestionOrder();
                questionRow.put("number", questionOrder == null ? Integer.valueOf(index) : questionOrder);
                questionRow.put("questionText", toPlainText(item.getQuestionText()));
                questionRow.put("choices", parseStringListJson(item.getChoicesJson()));
                questionRow.put("answerText", item.getAnswerText() == null || item.getAnswerText().isBlank() ? "-" : item.getAnswerText().trim());
                questionRow.put("difficulty", item.getDifficulty() == null || item.getDifficulty().isBlank() ? "Medium" : item.getDifficulty().trim());
                selectedQuizQuestions.add(questionRow);
                index++;
            }
        }

        model.addAttribute("departmentName", selectedDepartment.isBlank() ? "Department not set" : selectedDepartment);
        model.addAttribute("selectedDepartment", selectedDepartment);
        model.addAttribute("selectedProgram", selectedProgram);
        model.addAttribute("selectedSubject", selectedSubject);
        model.addAttribute("selectedQuiz", selectedQuiz);
        model.addAttribute("selectedQuizQuestions", selectedQuizQuestions);
        model.addAttribute("selectedExamId", selectedExamId);
        model.addAttribute("selectedExamName", selectedExamName);
        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("page", Math.max(0, page));
        model.addAttribute("size", Math.max(5, Math.min(size, 50)));
        return "department-admin-question-bank-detail";
    }

    @GetMapping("/department-view")
    public String departmentView() {
        return "redirect:/department-admin/dashboard";
    }

    private String toPlainText(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return "-";
        }
        return questionText
            .replaceAll("<[^>]+>", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private List<String> parseStringListJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        try {
            List<String> parsed = gson.fromJson(json, new TypeToken<List<String>>() { }.getType());
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (RuntimeException exception) {
            return new ArrayList<>();
        }
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

    private boolean sameProgram(String left, String right) {
        String leftValue = left == null ? "" : left.trim();
        String rightValue = right == null ? "" : right.trim();
        if (leftValue.isBlank() && rightValue.isBlank()) {
            return true;
        }
        return leftValue.equalsIgnoreCase(rightValue);
    }

    private List<String> buildProgramOptionsForDepartment(String departmentName,
                                                          List<User> teachersInDepartment,
                                                          User currentAdmin) {
        List<String> options = new ArrayList<>();

        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        if (!normalizedDepartment.isBlank()) {
            for (DepartmentProgram program : departmentProgramRepository.findByDepartmentNameIgnoreCaseOrderByProgramNameAsc(normalizedDepartment)) {
                if (program == null) {
                    continue;
                }
                addUniqueValue(options, program.getProgramName());
            }
        }

        for (String catalogProgram : AcademicCatalog.programsForDepartment(departmentName)) {
            addUniqueValue(options, catalogProgram);
        }

        if (currentAdmin != null) {
            addUniqueValue(options, currentAdmin.getProgramName());
        }

        for (User teacher : teachersInDepartment) {
            if (teacher == null) {
                continue;
            }
            addUniqueValue(options, teacher.getProgramName());
        }

        return options;
    }

    private void addUniqueValue(List<String> values, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return;
        }
        boolean exists = values.stream().anyMatch(existing -> existing.equalsIgnoreCase(normalized));
        if (!exists) {
            values.add(normalized);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private String toDomKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.isBlank()) {
            return "program";
        }

        String slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "program" : slug;
    }

    private String buildProgramQuestionBankRedirect(String departmentName, String programName) {
        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        String normalizedProgram = programName == null ? "" : programName.trim();
        if (normalizedDepartment.isBlank()) {
            return "redirect:/department-admin/dashboard";
        }

        String redirect = "redirect:/department-admin/question-bank?page=0&size=15&departmentName="
            + java.net.URLEncoder.encode(normalizedDepartment, StandardCharsets.UTF_8);
        if (!normalizedProgram.isBlank()) {
            redirect += "&programName=" + java.net.URLEncoder.encode(normalizedProgram, StandardCharsets.UTF_8);
        }
        return redirect;
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
        return columns.toArray(String[]::new);
    }
}
