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
import com.exam.entity.DepartmentSharingSetting;
import com.exam.entity.EnrolledStudent;
import com.exam.entity.OriginalProcessedPaper;
import com.exam.entity.QuestionBankItem;
import com.exam.entity.Subject;
import com.exam.entity.User;
import com.exam.repository.DepartmentProgramRepository;
import com.exam.repository.DepartmentSharingSettingRepository;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.OriginalProcessedPaperRepository;
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
    private final DepartmentSharingSettingRepository departmentSharingSettingRepository;
    private final OriginalProcessedPaperRepository originalProcessedPaperRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Gson gson = new Gson();

    public DepartmentAdminController(UserRepository userRepository,
                                     SubjectRepository subjectRepository,
                                     EnrolledStudentRepository enrolledStudentRepository,
                                     DepartmentProgramRepository departmentProgramRepository,
                                     DepartmentSharingSettingRepository departmentSharingSettingRepository,
                                     OriginalProcessedPaperRepository originalProcessedPaperRepository,
                                     BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.subjectRepository = subjectRepository;
        this.enrolledStudentRepository = enrolledStudentRepository;
        this.departmentProgramRepository = departmentProgramRepository;
        this.departmentSharingSettingRepository = departmentSharingSettingRepository;
        this.originalProcessedPaperRepository = originalProcessedPaperRepository;
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

        ensureCatalogProgramsPersisted(departmentName, adminEmail);

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

        List<QuestionBankItem> temporaryQuestionBank = buildTemporaryQuestionBankItems(originalProcessedPaperRepository.findAll());
        List<QuestionBankItem> departmentQuestionBank = temporaryQuestionBank.stream()
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

    @PostMapping("/sharing/teacher-pull")
    public String updateDepartmentTeacherPullSharing(@RequestParam(name = "enabled", required = false) Boolean enabled,
                                                     Principal principal,
                                                     RedirectAttributes redirectAttributes) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);
        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Department Admin can update sharing settings.");
            return "redirect:/department-admin/dashboard";
        }

        String departmentName = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        if (departmentName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account has no department assigned.");
            return "redirect:/department-admin/dashboard";
        }

        DepartmentSharingSetting setting = departmentSharingSettingRepository
            .findByDepartmentNameIgnoreCase(departmentName)
            .orElseGet(DepartmentSharingSetting::new);

        setting.setDepartmentName(departmentName);
        setting.setTeacherPullEnabled(Boolean.TRUE.equals(enabled));
        setting.setUpdatedByEmail(adminEmail);
        departmentSharingSettingRepository.save(setting);

        redirectAttributes.addFlashAttribute(
            "successMessage",
            Boolean.TRUE.equals(enabled)
                ? "Department pull access is ON. Department admins can now choose which quizzes are shared."
                : "Department pull access is OFF. Shared quizzes are hidden from teachers."
        );
        return "redirect:/department-admin/dashboard";
    }

    @PostMapping("/question-bank/quiz-sharing")
    public String updateQuizSharing(@RequestParam(name = "examId", required = false) String examId,
                                    @RequestParam(name = "enabled", required = false) Boolean enabled,
                                    @RequestParam(name = "shareScope", required = false) String shareScope,
                                    @RequestParam(name = "shareProgramName", required = false) String shareProgramName,
                                    @RequestParam(name = "shareTeacherEmail", required = false) String shareTeacherEmail,
                                    @RequestParam(name = "search", required = false) String search,
                                    @RequestParam(name = "departmentName", required = false) String departmentName,
                                    @RequestParam(name = "programName", required = false) String programName,
                                    @RequestParam(name = "subject", required = false) String subject,
                                    @RequestParam(name = "teacherSearch", required = false) String teacherSearch,
                                    @RequestParam(name = "teacherPage", required = false) Integer teacherPage,
                                    @RequestParam(name = "teacherSize", required = false) Integer teacherSize,
                                    @RequestParam(name = "teacherEmail", required = false) String teacherEmail,
                                    @RequestParam(name = "studentSearch", required = false) String studentSearch,
                                    @RequestParam(name = "quizSearch", required = false) String quizSearch,
                                    @RequestParam(name = "page", required = false) Integer page,
                                    @RequestParam(name = "size", required = false) Integer size,
                                    Principal principal,
                                    RedirectAttributes redirectAttributes) {
        String adminEmail = principal != null ? principal.getName() : "";
        User currentAdmin = adminEmail.isBlank() ? null : userRepository.findByEmail(adminEmail).orElse(null);

        String normalizedTeacherEmail = normalizeEmail(teacherEmail);
        addQuestionBankRedirectContext(
            redirectAttributes,
            search,
            departmentName,
            programName,
            subject,
            teacherSearch,
            teacherPage,
            teacherSize,
            page,
            size,
            normalizedTeacherEmail,
            studentSearch,
            quizSearch
        );

        if (currentAdmin == null || currentAdmin.getRole() != User.Role.DEPARTMENT_ADMIN) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Department Admin can update quiz sharing.");
            return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
        }

        String normalizedExamId = examId == null ? "" : examId.trim();
        if (normalizedExamId.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Quiz ID is missing.");
            return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
        }

        OriginalProcessedPaper paper = resolvePaperForSharingUpdate(normalizedExamId, normalizedTeacherEmail);
        if (paper == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Quiz not found for sharing update.");
            return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
        }

        String adminDepartment = currentAdmin.getDepartmentName() == null ? "" : currentAdmin.getDepartmentName().trim();
        String quizDepartment = resolvePaperDepartment(paper);
        if (!sameDepartment(quizDepartment, adminDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can update sharing only for quizzes in your department.");
            return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
        }

        boolean shareEnabled = Boolean.TRUE.equals(enabled);
        if (!shareEnabled) {
            paper.setTeacherPullShared(false);
            paper.setSharingScope("PRIVATE");
            paper.setSharedProgramName(null);
            paper.setSharedTeacherEmail(null);
        } else {
            String normalizedScope = normalizeSharingScope(shareScope);
            switch (normalizedScope) {
                case "PROGRAM" -> {
                    String programTarget = shareProgramName == null ? "" : shareProgramName.trim();
                    if (programTarget.isBlank()) {
                        redirectAttributes.addFlashAttribute("errorMessage", "Select a target program before sharing.");
                        return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
                    }

                    paper.setTeacherPullShared(true);
                    paper.setSharingScope("PROGRAM");
                    paper.setSharedProgramName(programTarget);
                    paper.setSharedTeacherEmail(null);
                }
                case "TEACHER" -> {
                    String specificProgramTarget = shareProgramName == null ? "" : shareProgramName.trim();
                    if (specificProgramTarget.isBlank()) {
                        redirectAttributes.addFlashAttribute("errorMessage", "Select a target program before choosing teacher.");
                        return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
                    }

                    String teacherTarget = normalizeEmail(shareTeacherEmail);
                    User targetTeacher = teacherTarget.isBlank() ? null : userRepository.findByEmail(teacherTarget).orElse(null);
                    if (targetTeacher == null || targetTeacher.getRole() != User.Role.TEACHER
                        || !sameDepartment(targetTeacher.getDepartmentName(), adminDepartment)) {
                        redirectAttributes.addFlashAttribute("errorMessage", "Target teacher must exist and belong to your department.");
                        return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
                    }

                    String targetTeacherProgram = targetTeacher.getProgramName() == null ? "" : targetTeacher.getProgramName().trim();
                    boolean programMatches = "Unassigned Program".equalsIgnoreCase(specificProgramTarget)
                        ? targetTeacherProgram.isBlank()
                        : sameProgram(targetTeacherProgram, specificProgramTarget);
                    if (!programMatches) {
                        redirectAttributes.addFlashAttribute("errorMessage", "Selected teacher must belong to the selected program.");
                        return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
                    }

                    paper.setTeacherPullShared(true);
                    paper.setSharingScope("TEACHER");
                    paper.setSharedProgramName(specificProgramTarget);
                    paper.setSharedTeacherEmail(teacherTarget);
                }
                default -> {
                    redirectAttributes.addFlashAttribute("errorMessage", "Unsupported sharing mode.");
                    return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
                }
            }
        }
        originalProcessedPaperRepository.save(paper);

        String quizName = paper.getExamName() == null || paper.getExamName().isBlank() ? "Untitled Quiz" : paper.getExamName().trim();
        String sharingLabel = buildSharingLabel(
            paper.isTeacherPullShared(),
            paper.getSharingScope(),
            paper.getSharedProgramName(),
            paper.getSharedTeacherEmail()
        );
        redirectAttributes.addFlashAttribute(
            "successMessage",
            shareEnabled
                ? "Sharing updated for \"" + quizName + "\": " + sharingLabel + "."
                : "Sharing disabled for \"" + quizName + "\"."
        );
        return resolveQuestionBankRedirectTarget(normalizedTeacherEmail);
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
                    if (!teacher.getDepartmentName().equals(normalizedDepartment)) {
                        teacher.setDepartmentName(normalizedDepartment);
                        changed = true;
                    }
                    
                    // Logic to handle multiple programs (comma-separated)
                    String currentProgramsStr = teacher.getProgramName() == null ? "" : teacher.getProgramName();
                    java.util.Set<String> programSet = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                    if (!currentProgramsStr.isBlank()) {
                        for (String p : currentProgramsStr.split(",")) {
                            if (!p.isBlank()) {
                                programSet.add(p.trim());
                            }
                        }
                    }
                    if (!normalizedProgram.isBlank()) {
                        programSet.add(normalizedProgram);
                    }
                    String newProgramsStr = String.join(", ", programSet);
                    
                    if (!currentProgramsStr.equals(newProgramsStr)) {
                        teacher.setProgramName(newProgramsStr);
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
                               @RequestParam(name = "teacherSearch", required = false) String teacherSearch,
                               @RequestParam(name = "teacherPage", defaultValue = "0") int teacherPage,
                               @RequestParam(name = "teacherSize", defaultValue = "6") int teacherSize,
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

        List<OriginalProcessedPaper> allPapers = originalProcessedPaperRepository.findAll();
        Map<String, OriginalProcessedPaper> papersByExamId = new HashMap<>();
        for (OriginalProcessedPaper paper : allPapers) {
            if (paper == null || paper.getExamId() == null || paper.getExamId().isBlank()) {
                continue;
            }
            papersByExamId.put(paper.getExamId().trim().toLowerCase(), paper);
        }

        List<QuestionBankItem> temporaryQuestionBank = buildTemporaryQuestionBankItems(allPapers);

        List<QuestionBankItem> scopedItems = selectedDepartment.isBlank()
            ? new ArrayList<>()
            : temporaryQuestionBank.stream()
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
                String paperKey = resolvedExamId.toLowerCase();
                OriginalProcessedPaper sourcePaper = paperKey.isBlank() ? null : papersByExamId.get(paperKey);
                boolean teacherPullShared = sourcePaper != null && sourcePaper.isTeacherPullShared();
                String sharingScope = sourcePaper == null ? "" : normalizeSharingScope(sourcePaper.getSharingScope());
                String sharedProgramName = sourcePaper == null || sourcePaper.getSharedProgramName() == null ? "" : sourcePaper.getSharedProgramName().trim();
                String sharedTeacherEmail = sourcePaper == null || sourcePaper.getSharedTeacherEmail() == null ? "" : sourcePaper.getSharedTeacherEmail().trim();
                if (teacherPullShared && sharingScope.isBlank()) {
                    teacherPullShared = false;
                }
                quizRow.put("sourceExamId", resolvedExamId);
                quizRow.put("sourceExamName", resolvedExamName);
                quizRow.put("subject", item.getSubject() == null ? "" : item.getSubject().trim());
                quizRow.put("sourceTeacherEmail", sourceTeacherEmail);
                quizRow.put("sourceTeacherDepartment", resolvedDepartment);
                quizRow.put("sourceTeacherProgram", resolvedProgram);
                quizRow.put("teacherPullShared", teacherPullShared);
                quizRow.put("sharingScope", sharingScope);
                quizRow.put("sharedProgramName", sharedProgramName);
                quizRow.put("sharedTeacherEmail", sharedTeacherEmail);
                quizRow.put("sharingLabel", buildSharingLabel(teacherPullShared, sharingScope, sharedProgramName, sharedTeacherEmail));
                quizRow.put("questionCount", 0);
                quizRow.put("createdAt", item.getCreatedAt());
                quizRowsByKey.put(quizKey, quizRow);
            }

            int questionCount = (Integer) quizRow.getOrDefault("questionCount", 0);
            quizRow.put("questionCount", questionCount + 1);
        }

        List<User> teacherOptions = teachersInDepartment.stream()
            .sorted(Comparator.comparing(
                teacher -> teacher.getFullName() == null || teacher.getFullName().isBlank() ? teacher.getEmail() : teacher.getFullName(),
                String.CASE_INSENSITIVE_ORDER
            ))
            .toList();

        Map<String, Integer> quizzesByTeacherCount = new HashMap<>();
        for (Map<String, Object> quizRow : quizRowsByKey.values()) {
            String teacherEmail = normalizeEmail(String.valueOf(quizRow.getOrDefault("sourceTeacherEmail", "")));
            if (teacherEmail.isBlank()) {
                continue;
            }

            quizzesByTeacherCount.merge(teacherEmail, 1, Integer::sum);
        }

        Map<String, Set<String>> studentEmailsByTeacher = new HashMap<>();
        for (EnrolledStudent enrolledStudent : enrolledStudentRepository.findAll()) {
            if (enrolledStudent == null || enrolledStudent.getTeacherEmail() == null) {
                continue;
            }

            String teacherEmail = normalizeEmail(enrolledStudent.getTeacherEmail());
            if (teacherEmail.isBlank() || !teacherEmailSetInScope.contains(teacherEmail)) {
                continue;
            }

            String studentEmail = normalizeEmail(enrolledStudent.getStudentEmail());
            if (studentEmail.isBlank()) {
                continue;
            }

            studentEmailsByTeacher
                .computeIfAbsent(teacherEmail, key -> new java.util.HashSet<>())
                .add(studentEmail);
        }

        String normalizedTeacherSearch = teacherSearch == null ? "" : teacherSearch.trim().toLowerCase();
        List<Map<String, Object>> teacherInsights = new ArrayList<>();
        for (User teacher : teacherOptions) {
            if (teacher == null || teacher.getEmail() == null) {
                continue;
            }

            String teacherEmail = normalizeEmail(teacher.getEmail());
            String teacherName = teacher.getFullName() == null || teacher.getFullName().isBlank()
                ? teacher.getEmail().trim()
                : teacher.getFullName().trim();

            if (!normalizedTeacherSearch.isBlank()) {
                String searchableTeacher = (teacherName + " " + teacherEmail).toLowerCase();
                if (!searchableTeacher.contains(normalizedTeacherSearch)) {
                    continue;
                }
            }

            Map<String, Object> teacherInsight = new LinkedHashMap<>();
            teacherInsight.put("teacherName", teacherName);
            teacherInsight.put("teacherEmail", teacher.getEmail().trim());
            teacherInsight.put("studentCount", studentEmailsByTeacher.getOrDefault(teacherEmail, new java.util.HashSet<>()).size());
            teacherInsight.put("quizCount", quizzesByTeacherCount.getOrDefault(teacherEmail, 0));
            teacherInsights.add(teacherInsight);
        }

        int safeTeacherSize = Math.max(4, Math.min(teacherSize, 30));
        int teacherTotal = teacherInsights.size();
        int teacherTotalPages = Math.max(1, (int) Math.ceil(teacherTotal / (double) safeTeacherSize));
        int safeTeacherPage = Math.max(0, Math.min(teacherPage, teacherTotalPages - 1));
        int teacherFrom = safeTeacherPage * safeTeacherSize;
        int teacherTo = Math.min(teacherFrom + safeTeacherSize, teacherTotal);
        List<Map<String, Object>> pagedTeacherInsights = teacherFrom < teacherTo
            ? teacherInsights.subList(teacherFrom, teacherTo)
            : new ArrayList<>();

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
        boolean canManageImports = !selectedProgram.isBlank() && !"Unassigned Program".equalsIgnoreCase(selectedProgram);
        model.addAttribute("teacherOptions", canManageImports ? teacherOptions : new ArrayList<>());
        model.addAttribute("teacherInsights", pagedTeacherInsights);
        model.addAttribute("teacherSearch", teacherSearch == null ? "" : teacherSearch.trim());
        model.addAttribute("teacherPage", safeTeacherPage);
        model.addAttribute("teacherSize", safeTeacherSize);
        model.addAttribute("teacherTotal", teacherTotal);
        model.addAttribute("teacherTotalPages", teacherTotalPages);
        model.addAttribute("hasTeacherPrev", safeTeacherPage > 0);
        model.addAttribute("hasTeacherNext", safeTeacherPage + 1 < teacherTotalPages);
        model.addAttribute("canManageImports", canManageImports);

        List<User> programStudents = new ArrayList<>();
        if (canManageImports) {
             programStudents = userRepository.findAll().stream()
                .filter(user -> user != null && user.getRole() == User.Role.STUDENT)
                .filter(user -> selectedDepartment.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
                .filter(user -> sameProgram(user.getProgramName(), selectedProgram))
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
        model.addAttribute("programTeachers", teacherOptions); // Reuse teacherOptions which is already filtered by program/department
        model.addAttribute("programStudents", programStudents);

        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("page", safePage);
        model.addAttribute("size", safeSize);
        model.addAttribute("total", total);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("hasPrev", safePage > 0);
        model.addAttribute("hasNext", safePage + 1 < totalPages);
        return "department-admin-question-bank";
    }

    @GetMapping("/question-bank/teacher")
    public String questionBankTeacherDetail(@RequestParam(name = "departmentName", required = false) String departmentName,
                                            @RequestParam(name = "programName", required = false) String programName,
                                            @RequestParam(name = "teacherEmail", required = false) String teacherEmail,
                                            @RequestParam(name = "subject", required = false) String subject,
                                            @RequestParam(name = "search", required = false) String search,
                                            @RequestParam(name = "teacherSearch", required = false) String teacherSearch,
                                            @RequestParam(name = "studentSearch", required = false) String studentSearch,
                                            @RequestParam(name = "quizSearch", required = false) String quizSearch,
                                            @RequestParam(name = "teacherPage", defaultValue = "0") int teacherPage,
                                            @RequestParam(name = "teacherSize", defaultValue = "6") int teacherSize,
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
        final String selectedTeacherEmail = normalizeEmail(teacherEmail);

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

        User selectedTeacher = teachersInDepartment.stream()
            .filter(teacher -> teacher.getEmail() != null && selectedTeacherEmail.equals(normalizeEmail(teacher.getEmail())))
            .findFirst()
            .orElse(null);

        if (selectedTeacher == null) {
            return "redirect:/department-admin/question-bank";
        }

        List<User> shareTargetTeachers = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
            .filter(user -> selectedDepartment.isBlank()
                || selectedDepartment.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
            .toList();

        Set<String> shareProgramsSet = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String programOption : buildProgramOptionsForDepartment(selectedDepartment, shareTargetTeachers, currentAdmin)) {
            if (programOption != null && !programOption.isBlank()) {
                shareProgramsSet.add(programOption.trim());
            }
        }
        Map<String, List<Map<String, String>>> shareTeachersByProgram = new LinkedHashMap<>();
        for (User teacher : shareTargetTeachers) {
            String teacherEmailValue = normalizeEmail(teacher.getEmail());
            if (teacherEmailValue.isBlank()) {
                continue;
            }

            String teacherProgramValue = teacher.getProgramName() == null ? "" : teacher.getProgramName().trim();
            String programBucket = teacherProgramValue.isBlank() ? "Unassigned Program" : teacherProgramValue;
            shareProgramsSet.add(programBucket);

            String displayName = teacher.getFullName() == null || teacher.getFullName().isBlank()
                ? teacherEmailValue
                : teacher.getFullName().trim() + " (" + teacherEmailValue + ")";

            Map<String, String> teacherOption = new LinkedHashMap<>();
            teacherOption.put("email", teacherEmailValue);
            teacherOption.put("label", displayName);
            shareTeachersByProgram.computeIfAbsent(programBucket, ignored -> new ArrayList<>()).add(teacherOption);
        }

        List<String> sharePrograms = new ArrayList<>(shareProgramsSet);
        for (String program : sharePrograms) {
            List<Map<String, String>> teachers = shareTeachersByProgram.get(program);
            if (teachers == null) {
                continue;
            }
            teachers.sort(Comparator.comparing(
                entry -> entry.getOrDefault("label", ""),
                String.CASE_INSENSITIVE_ORDER
            ));
        }

        String defaultShareProgram = selectedProgram;
        if (defaultShareProgram.isBlank() || !sharePrograms.contains(defaultShareProgram)) {
            defaultShareProgram = sharePrograms.isEmpty() ? "" : sharePrograms.get(0);
        }

        Map<String, String> studentsByEmail = new LinkedHashMap<>();
        for (EnrolledStudent enrolledStudent : enrolledStudentRepository.findAll()) {
            if (enrolledStudent == null || enrolledStudent.getTeacherEmail() == null) {
                continue;
            }

            if (!selectedTeacherEmail.equals(normalizeEmail(enrolledStudent.getTeacherEmail()))) {
                continue;
            }

            String studentEmail = normalizeEmail(enrolledStudent.getStudentEmail());
            if (studentEmail.isBlank()) {
                continue;
            }

            String studentName = enrolledStudent.getStudentName() == null || enrolledStudent.getStudentName().isBlank()
                ? studentEmail
                : enrolledStudent.getStudentName().trim();
            studentsByEmail.putIfAbsent(studentEmail, studentName);
        }

        String normalizedStudentSearch = studentSearch == null ? "" : studentSearch.trim().toLowerCase();
        List<Map<String, String>> enrolledStudents = studentsByEmail.entrySet().stream()
            .map(entry -> {
                Map<String, String> studentRow = new LinkedHashMap<>();
                studentRow.put("email", entry.getKey());
                studentRow.put("name", entry.getValue());
                return studentRow;
            })
            .filter(row -> {
                if (normalizedStudentSearch.isBlank()) {
                    return true;
                }
                String studentName = row.getOrDefault("name", "").toLowerCase();
                String studentEmail = row.getOrDefault("email", "").toLowerCase();
                return studentName.contains(normalizedStudentSearch) || studentEmail.contains(normalizedStudentSearch);
            })
            .sorted(Comparator.comparing(row -> row.getOrDefault("name", ""), String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<OriginalProcessedPaper> allPapers = originalProcessedPaperRepository.findAll();
        Map<String, OriginalProcessedPaper> papersByExamId = new HashMap<>();
        for (OriginalProcessedPaper paper : allPapers) {
            if (paper == null || paper.getExamId() == null || paper.getExamId().isBlank()) {
                continue;
            }
            papersByExamId.put(paper.getExamId().trim().toLowerCase(), paper);
        }

        Map<String, Map<String, Object>> quizzesByKey = new LinkedHashMap<>();
        for (QuestionBankItem item : buildTemporaryQuestionBankItems(allPapers)) {
            if (item == null) {
                continue;
            }

            if (!selectedTeacherEmail.equals(normalizeEmail(item.getSourceTeacherEmail()))) {
                continue;
            }

            if (!selectedSubject.isBlank()) {
                String itemSubject = item.getSubject() == null ? "" : item.getSubject().trim();
                if (!selectedSubject.equalsIgnoreCase(itemSubject)) {
                    continue;
                }
            }

            String examId = item.getSourceExamId() == null ? "" : item.getSourceExamId().trim();
            String examName = item.getSourceExamName() == null ? "" : item.getSourceExamName().trim();
            if (examName.isBlank()) {
                examName = "Untitled Quiz";
            }

            String quizKey = !examId.isBlank()
                ? "id::" + examId.toLowerCase()
                : "name::" + examName.toLowerCase();

            Map<String, Object> quizRow = quizzesByKey.get(quizKey);
            if (quizRow == null) {
                quizRow = new LinkedHashMap<>();
                String paperKey = examId.toLowerCase();
                OriginalProcessedPaper sourcePaper = paperKey.isBlank() ? null : papersByExamId.get(paperKey);
                boolean teacherPullShared = sourcePaper != null && sourcePaper.isTeacherPullShared();
                String sharingScope = sourcePaper == null ? "" : normalizeSharingScope(sourcePaper.getSharingScope());
                String sharedProgramName = sourcePaper == null || sourcePaper.getSharedProgramName() == null ? "" : sourcePaper.getSharedProgramName().trim();
                String sharedTeacherEmail = sourcePaper == null || sourcePaper.getSharedTeacherEmail() == null ? "" : sourcePaper.getSharedTeacherEmail().trim();
                if (teacherPullShared && sharingScope.isBlank()) {
                    teacherPullShared = false;
                }
                quizRow.put("sourceExamId", examId);
                quizRow.put("sourceExamName", examName);
                quizRow.put("subject", item.getSubject() == null ? "" : item.getSubject().trim());
                quizRow.put("teacherPullShared", teacherPullShared);
                quizRow.put("sharingScope", sharingScope);
                quizRow.put("sharedProgramName", sharedProgramName);
                quizRow.put("sharedTeacherEmail", sharedTeacherEmail);
                quizRow.put("sharingLabel", buildSharingLabel(teacherPullShared, sharingScope, sharedProgramName, sharedTeacherEmail));
                quizRow.put("questionCount", 0);
                quizRow.put("createdAt", item.getCreatedAt());
                quizzesByKey.put(quizKey, quizRow);
            }

            int questionCount = (Integer) quizRow.getOrDefault("questionCount", 0);
            quizRow.put("questionCount", questionCount + 1);
        }

        String normalizedQuizSearch = quizSearch == null ? "" : quizSearch.trim().toLowerCase();
        List<Map<String, Object>> teacherQuizzes = quizzesByKey.values().stream()
            .filter(quiz -> {
                if (normalizedQuizSearch.isBlank()) {
                    return true;
                }
                String quizName = String.valueOf(quiz.getOrDefault("sourceExamName", "")).toLowerCase();
                String quizSubject = String.valueOf(quiz.getOrDefault("subject", "")).toLowerCase();
                return quizName.contains(normalizedQuizSearch) || quizSubject.contains(normalizedQuizSearch);
            })
            .toList();

        model.addAttribute("departmentName", selectedDepartment.isBlank() ? "Department not set" : selectedDepartment);
        model.addAttribute("selectedDepartment", selectedDepartment);
        model.addAttribute("selectedProgram", selectedProgram);
        model.addAttribute("selectedSubject", selectedSubject);
        model.addAttribute("selectedTeacherEmail", selectedTeacherEmail);
        model.addAttribute("selectedTeacherName", selectedTeacher.getFullName() == null || selectedTeacher.getFullName().isBlank()
            ? selectedTeacher.getEmail()
            : selectedTeacher.getFullName().trim());
        model.addAttribute("enrolledStudents", enrolledStudents);
        model.addAttribute("teacherQuizzes", teacherQuizzes);
        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("teacherSearch", teacherSearch == null ? "" : teacherSearch.trim());
        model.addAttribute("studentSearch", studentSearch == null ? "" : studentSearch.trim());
        model.addAttribute("quizSearch", quizSearch == null ? "" : quizSearch.trim());
        model.addAttribute("sharePrograms", sharePrograms);
        model.addAttribute("defaultShareProgram", defaultShareProgram);
        model.addAttribute("shareTeachersByProgram", shareTeachersByProgram);
        model.addAttribute("teacherPage", Math.max(0, teacherPage));
        model.addAttribute("teacherSize", Math.max(4, Math.min(teacherSize, 30)));
        model.addAttribute("page", Math.max(0, page));
        model.addAttribute("size", Math.max(5, Math.min(size, 50)));
        return "department-admin-question-bank-teacher";
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

        List<QuestionBankItem> temporaryQuestionBank = buildTemporaryQuestionBankItems(originalProcessedPaperRepository.findAll());

        List<QuestionBankItem> selectedQuizItems = selectedDepartment.isBlank()
            ? new ArrayList<>()
            : temporaryQuestionBank.stream()
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
                List<String> choices = parseStringListJson(item.getChoicesJson());
                String answerText = item.getAnswerText() == null || item.getAnswerText().isBlank() ? "-" : item.getAnswerText().trim();
                String questionText = toPlainText(item.getQuestionText());
                boolean openEnded = isOpenEndedQuestion(questionText, answerText, choices);
                questionRow.put("number", questionOrder == null ? Integer.valueOf(index) : questionOrder);
                questionRow.put("questionText", questionText);
                questionRow.put("choices", choices);
                questionRow.put("openEnded", openEnded);
                questionRow.put("labeledChoices", openEnded ? new ArrayList<>() : buildLabeledChoices(choices));
                questionRow.put("answerText", answerText);
                questionRow.put("answerDisplay", openEnded ? answerText : resolveAnswerDisplay(answerText, choices));
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

    private List<Map<String, String>> buildLabeledChoices(List<String> choices) {
        List<Map<String, String>> labeledChoices = new ArrayList<>();
        if (choices == null || choices.isEmpty()) {
            return labeledChoices;
        }

        for (int index = 0; index < choices.size(); index++) {
            String choiceText = choices.get(index) == null ? "" : choices.get(index).trim();
            if (choiceText.isBlank()) {
                continue;
            }

            Map<String, String> option = new LinkedHashMap<>();
            option.put("label", toOptionLabel(index));
            option.put("text", choiceText);
            labeledChoices.add(option);
        }
        return labeledChoices;
    }

    private String resolveAnswerDisplay(String answerText, List<String> choices) {
        if (answerText == null || answerText.isBlank() || "-".equals(answerText.trim())) {
            return "-";
        }

        String normalizedAnswer = normalizeAnswerToken(answerText);
        if (choices != null) {
            for (int index = 0; index < choices.size(); index++) {
                String label = toOptionLabel(index);
                String choiceText = choices.get(index) == null ? "" : choices.get(index).trim();
                if (normalizedAnswer.equalsIgnoreCase(label) || normalizeAnswerToken(choiceText).equalsIgnoreCase(normalizedAnswer)) {
                    return choiceText.isBlank() ? label : (label + " - " + choiceText);
                }
            }
        }

        return answerText.trim();
    }

    private boolean isOpenEndedQuestion(String questionText, String answerText, List<String> choices) {
        String normalizedQuestion = questionText == null ? "" : questionText.trim().toLowerCase();
        if (normalizedQuestion.contains("[essay]")
            || normalizedQuestion.contains("[open-ended]")
            || normalizedQuestion.contains("[open ended]")
            || normalizedQuestion.contains("[text_input]")
            || normalizedQuestion.contains("[text input]")) {
            return true;
        }

        List<String> nonEmptyChoices = choices == null
            ? new ArrayList<>()
            : choices.stream()
                .filter(choice -> choice != null && !choice.trim().isBlank())
                .toList();
        if (nonEmptyChoices.isEmpty()) {
            return true;
        }

        if (answerText == null || answerText.isBlank() || "-".equals(answerText.trim())) {
            return false;
        }

        if (isChoiceMappedAnswer(answerText, nonEmptyChoices)) {
            return false;
        }

        int wordCount = answerText.trim().split("\\s+").length;
        return wordCount >= 4;
    }

    private boolean isChoiceMappedAnswer(String answerText, List<String> choices) {
        String normalizedAnswer = normalizeAnswerToken(answerText);
        if (normalizedAnswer.isBlank() || choices == null || choices.isEmpty()) {
            return false;
        }

        for (int index = 0; index < choices.size(); index++) {
            String label = toOptionLabel(index);
            String choiceText = choices.get(index) == null ? "" : choices.get(index).trim();
            if (normalizedAnswer.equalsIgnoreCase(label) || normalizeAnswerToken(choiceText).equalsIgnoreCase(normalizedAnswer)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeAnswerToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String token = value.trim();
        if (token.startsWith("(") && token.endsWith(")") && token.length() > 2) {
            token = token.substring(1, token.length() - 1).trim();
        }

        token = token.replaceAll("\\s+", "");
        if (token.regionMatches(true, 0, "choice_", 0, 7)) {
            token = token.substring(7);
        } else if (token.regionMatches(true, 0, "choice", 0, 6)) {
            token = token.substring(6);
        }

        if (token.matches("\\d+")) {
            try {
                int numericIndex = Integer.parseInt(token);
                if (numericIndex > 0) {
                    return toOptionLabel(numericIndex - 1);
                }
            } catch (NumberFormatException exception) {
                return token.toUpperCase();
            }
        }

        return token.toUpperCase();
    }

    private String toOptionLabel(int index) {
        if (index < 0) {
            return "";
        }

        int value = index;
        StringBuilder label = new StringBuilder();
        do {
            int remainder = value % 26;
            label.insert(0, (char) ('A' + remainder));
            value = (value / 26) - 1;
        } while (value >= 0);

        return label.toString();
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

    private List<QuestionBankItem> buildTemporaryQuestionBankItems(List<OriginalProcessedPaper> papers) {
        List<QuestionBankItem> items = new ArrayList<>();
        if (papers == null || papers.isEmpty()) {
            return items;
        }

        for (OriginalProcessedPaper paper : papers) {
            if (paper == null || paper.getExamId() == null || paper.getExamId().isBlank()) {
                continue;
            }

            List<Map<String, Object>> questionRows = parseQuestionRowsJson(paper.getOriginalQuestionsJson());
            Map<String, String> difficultyMap = parseSimpleStringMapJson(paper.getDifficultiesJson());
            Map<String, String> answerKeyMap = parseSimpleStringMapJson(paper.getAnswerKeyJson());

            for (int index = 0; index < questionRows.size(); index++) {
                Map<String, Object> row = questionRows.get(index);
                if (row == null) {
                    continue;
                }

                String key = String.valueOf(index + 1);
                String questionText = toPlainText(String.valueOf(row.getOrDefault("question", "")));

                QuestionBankItem item = new QuestionBankItem();
                item.setSourceExamId(paper.getExamId());
                item.setSourceExamName(paper.getExamName());
                item.setSourceTeacherEmail(paper.getTeacherEmail());
                item.setSourceTeacherDepartment(paper.getDepartmentName());
                item.setSubject(paper.getSubject());
                item.setActivityType(paper.getActivityType());
                item.setQuestionOrder(index + 1);
                item.setQuestionText(questionText);
                item.setChoicesJson(gson.toJson(extractChoicesFromQuestionRow(row)));
                item.setAnswerText(answerKeyMap.getOrDefault(key, ""));
                item.setDifficulty(difficultyMap.getOrDefault(key, "Medium"));
                item.setCreatedAt(paper.getProcessedAt());
                items.add(item);
            }
        }

        return items;
    }

    private List<Map<String, Object>> parseQuestionRowsJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> parsed = gson.fromJson(json,
                new TypeToken<List<Map<String, Object>>>() { }.getType());
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (RuntimeException exception) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> parseSimpleStringMapJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> parsed = gson.fromJson(json,
                new TypeToken<Map<String, String>>() { }.getType());
            return parsed == null ? new HashMap<>() : parsed;
        } catch (RuntimeException exception) {
            return new HashMap<>();
        }
    }

    private List<String> extractChoicesFromQuestionRow(Map<String, Object> row) {
        List<String> choices = new ArrayList<>();
        if (row == null) {
            return choices;
        }

        Object choicesObj = row.get("choices");
        if (choicesObj instanceof List<?> list) {
            for (Object item : list) {
                String normalized = toPlainText(item == null ? "" : String.valueOf(item));
                if (!normalized.isBlank() && !"-".equals(normalized)) {
                    choices.add(normalized);
                }
            }
            return choices;
        }

        if (choicesObj instanceof String text && !text.isBlank()) {
            for (String token : text.split("\\r?\\n|,")) {
                String normalized = toPlainText(token);
                if (!normalized.isBlank() && !"-".equals(normalized)) {
                    choices.add(normalized);
                }
            }
        }

        return choices;
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
        String rightValue = right == null ? "" : right.trim();
        if (rightValue.isBlank()) {
            return true;
        }

        String leftValue = left == null ? "" : left.trim();
        if (leftValue.isBlank()) {
            return false;
        }

        for (String prog : leftValue.split(",")) {
            if (prog.trim().equalsIgnoreCase(rightValue)) {
                return true;
            }
        }
        return false;
    }

    private void ensureCatalogProgramsPersisted(String departmentName, String createdByEmail) {
        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        if (normalizedDepartment.isBlank()) {
            return;
        }

        List<String> catalogPrograms = AcademicCatalog.programsForDepartment(normalizedDepartment);
        if (catalogPrograms == null || catalogPrograms.isEmpty()) {
            return;
        }

        Set<String> existingProgramKeys = departmentProgramRepository
            .findByDepartmentNameIgnoreCaseOrderByProgramNameAsc(normalizedDepartment)
            .stream()
            .map(DepartmentProgram::getProgramName)
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toLowerCase())
            .collect(Collectors.toSet());

        List<DepartmentProgram> toSave = new ArrayList<>();
        for (String programName : catalogPrograms) {
            String normalizedProgram = programName == null ? "" : programName.trim();
            if (normalizedProgram.isBlank()) {
                continue;
            }

            if (existingProgramKeys.add(normalizedProgram.toLowerCase())) {
                DepartmentProgram departmentProgram = new DepartmentProgram();
                departmentProgram.setDepartmentName(normalizedDepartment);
                departmentProgram.setProgramName(normalizedProgram);
                departmentProgram.setCreatedByEmail(createdByEmail == null ? "system" : createdByEmail.trim());
                toSave.add(departmentProgram);
            }
        }

        if (!toSave.isEmpty()) {
            departmentProgramRepository.saveAll(toSave);
        }
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

        if (currentAdmin != null && currentAdmin.getProgramName() != null) {
            for (String p : currentAdmin.getProgramName().split(",")) {
                addUniqueValue(options, p.trim());
            }
        }

        for (User teacher : teachersInDepartment) {
            if (teacher == null || teacher.getProgramName() == null) {
                continue;
            }
            for (String p : teacher.getProgramName().split(",")) {
                addUniqueValue(options, p.trim());
            }
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

    private String resolveQuestionBankRedirectTarget(String teacherEmail) {
        return teacherEmail == null || teacherEmail.isBlank()
            ? "redirect:/department-admin/question-bank"
            : "redirect:/department-admin/question-bank/teacher";
    }

    private void addQuestionBankRedirectContext(RedirectAttributes redirectAttributes,
                                                String search,
                                                String departmentName,
                                                String programName,
                                                String subject,
                                                String teacherSearch,
                                                Integer teacherPage,
                                                Integer teacherSize,
                                                Integer page,
                                                Integer size,
                                                String teacherEmail,
                                                String studentSearch,
                                                String quizSearch) {
        if (redirectAttributes == null) {
            return;
        }

        addRedirectAttributeIfNotBlank(redirectAttributes, "search", search);
        addRedirectAttributeIfNotBlank(redirectAttributes, "departmentName", departmentName);
        addRedirectAttributeIfNotBlank(redirectAttributes, "programName", programName);
        addRedirectAttributeIfNotBlank(redirectAttributes, "subject", subject);
        addRedirectAttributeIfNotBlank(redirectAttributes, "teacherSearch", teacherSearch);
        if (teacherPage != null) {
            redirectAttributes.addAttribute("teacherPage", Math.max(0, teacherPage));
        }
        if (teacherSize != null) {
            redirectAttributes.addAttribute("teacherSize", Math.max(4, Math.min(teacherSize, 30)));
        }
        if (page != null) {
            redirectAttributes.addAttribute("page", Math.max(0, page));
        }
        if (size != null) {
            redirectAttributes.addAttribute("size", Math.max(5, Math.min(size, 50)));
        }
        addRedirectAttributeIfNotBlank(redirectAttributes, "teacherEmail", teacherEmail);
        addRedirectAttributeIfNotBlank(redirectAttributes, "studentSearch", studentSearch);
        addRedirectAttributeIfNotBlank(redirectAttributes, "quizSearch", quizSearch);
    }

    private void addRedirectAttributeIfNotBlank(RedirectAttributes redirectAttributes, String name, String value) {
        if (redirectAttributes == null || name == null || name.isBlank() || value == null || value.isBlank()) {
            return;
        }
        redirectAttributes.addAttribute(name, value.trim());
    }

    private String resolvePaperDepartment(OriginalProcessedPaper paper) {
        if (paper == null) {
            return "";
        }

        String paperDepartment = paper.getDepartmentName() == null ? "" : paper.getDepartmentName().trim();
        if (!paperDepartment.isBlank()) {
            return paperDepartment;
        }

        String ownerEmail = paper.getTeacherEmail() == null ? "" : paper.getTeacherEmail().trim();
        if (ownerEmail.isBlank()) {
            return "";
        }

        return userRepository.findByEmail(ownerEmail)
            .map(User::getDepartmentName)
            .map(String::trim)
            .orElse("");
    }

    private OriginalProcessedPaper resolvePaperForSharingUpdate(String examId, String teacherEmail) {
        String normalizedExamId = examId == null ? "" : examId.trim();
        if (normalizedExamId.isBlank()) {
            return null;
        }

        OriginalProcessedPaper directMatch = originalProcessedPaperRepository.findByExamId(normalizedExamId).orElse(null);
        if (directMatch != null) {
            return directMatch;
        }

        String normalizedTeacherEmail = normalizeEmail(teacherEmail);
        return originalProcessedPaperRepository.findAll().stream()
            .filter(paper -> paper != null && paper.getExamId() != null)
            .filter(paper -> normalizedExamId.equalsIgnoreCase(paper.getExamId().trim()))
            .filter(paper -> normalizedTeacherEmail.isBlank()
                || normalizedTeacherEmail.equals(normalizeEmail(paper.getTeacherEmail())))
            .findFirst()
            .orElse(null);
    }

    private String normalizeSharingScope(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if ("PROGRAM".equals(normalized) || "TEACHER".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String buildSharingLabel(boolean enabled,
                                     String scope,
                                     String sharedProgramName,
                                     String sharedTeacherEmail) {
        if (!enabled) {
            return "Private";
        }

        String normalizedScope = normalizeSharingScope(scope);
        if ("PROGRAM".equals(normalizedScope)) {
            String program = sharedProgramName == null ? "" : sharedProgramName.trim();
            return program.isBlank() ? "Program" : ("Program: " + program);
        }
        if ("TEACHER".equals(normalizedScope)) {
            String teacher = sharedTeacherEmail == null ? "" : sharedTeacherEmail.trim();
            return teacher.isBlank() ? "Specific Teacher" : ("Teacher: " + teacher);
        }
        return "Shared";
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
