package com.exam.Controller;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.HtmlUtils;

import com.exam.config.AcademicCatalog;
import com.exam.entity.DistributedExam;
import com.exam.entity.EnrolledStudent;
import com.exam.entity.ExamSubmission;
import com.exam.entity.OriginalProcessedPaper;
import com.exam.entity.QuestionBankItem;
import com.exam.entity.Subject;
import com.exam.entity.User;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.ExamSubmissionRepository;
import com.exam.repository.OriginalProcessedPaperRepository;
import com.exam.repository.SubjectRepository;
import com.exam.repository.UserRepository;
import com.exam.service.FisherYatesService;
import com.exam.service.UploadStorageService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

@Controller
@RequestMapping("/teacher")
@SuppressWarnings("all")
public class TeacherController {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeacherController.class);

    @Autowired
    private com.exam.repository.DistributedExamRepository distributedExamRepository;

    private static final DateTimeFormatter DEADLINE_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");

    private static final String SCHOOL_NAME = "Emilio Aguinaldo College";
    private static final String CAMPUS_NAME = "Manila";
    private static final String DEFAULT_IMPORTED_STUDENT_PASSWORD = "Student123!";
    private static final int ACCESS_OTP_LENGTH = 6;
    private static final long ACCESS_OTP_VALID_MINUTES = 10L;
    private static final List<String> DEPARTMENTS = List.of(
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

    private static final Pattern NUMBERED_QUESTION_PATTERN =
        Pattern.compile("(?m)^\\s*(\\d+)\\s*[\\).:-]\\s*(.+)$");

    private static final Pattern INLINE_ANSWER_PATTERN =
        Pattern.compile("(?i)\\banswer\\s*[:\\-]\\s*([A-Z]|\\d+|[^\\r\\n]+)");

    private static final Pattern ANSWER_KEY_PATTERN =
        Pattern.compile("(?m)^\\s*(\\d+)\\s*[\\).:-]?\\s*([A-Z]|\\d+|[^\\r\\n]+)\\s*$");

    private static final Pattern HTML_TAG_PATTERN =
        Pattern.compile("<\\/?[a-z][\\s\\S]*?>", Pattern.CASE_INSENSITIVE);

    private static final Pattern ESCAPED_HTML_TAG_PATTERN =
        Pattern.compile("&lt;\\/?[a-z][\\s\\S]*?&gt;", Pattern.CASE_INSENSITIVE);

    private final Gson gson = new Gson();

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private EnrolledStudentRepository enrolledStudentRepository;

    @Autowired
    private ExamSubmissionRepository examSubmissionRepository;

    @Autowired
    private OriginalProcessedPaperRepository originalProcessedPaperRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FisherYatesService fisherYatesService;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    @Autowired
    private UploadStorageService uploadStorageService;

    @GetMapping("/homepage")
    public String homepage(Model model, Principal principal) {
        return "redirect:/teacher/department-dashboard";
    }

    @GetMapping("/loading")
    public String loading() {
        return "teacher-loading";
    }

    @GetMapping("/csv-template")
    public ResponseEntity<byte[]> downloadCsvTemplate() {
        String template = String.join("\n",
            "ID,Difficulty,Question_Text,Choice_A,Choice_B,Choice_C,Choice_D,Correct_Answer",
            "1,Easy,What does 'URL' stand for?,\"Uniform Resource Locator\",\"Universal Resource Link\",,,A",
            "2,Medium,Which is an input device?,\"Monitor\",\"Keyboard\",\"Printer\",\"Speaker\",B",
            "3,Hard,Define 'Software' in your own words.,,,,,\"A set of instructions or programs that tell hardware what to do.\""
        ) + "\n";

        byte[] content = template.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=exam-upload-template.csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(content);
    }
    @GetMapping("/export-exam-with-answers/{format}")
    public ResponseEntity<byte[]> exportExamWithAnswers(@PathVariable("format") String format,
                                                        @RequestParam("examId") String examId,
                                                        Principal principal) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<ExportQuestionRow> rows = buildExportQuestionRows(paper);
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase();

        try {
            return switch (normalizedFormat) {
                case "csv" -> buildCsvExportResponse(paper, rows);
                case "pdf" -> buildPdfExportResponse(paper, rows);
                case "docx", "word" -> buildDocxExportResponse(paper, rows);
                default -> ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Unsupported export format.".getBytes(StandardCharsets.UTF_8));
            };
        } catch (Exception exception) {
            LOGGER.error("Failed to export exam. examId={}, format={}", examId, format, exception);
            return ResponseEntity.internalServerError()
                .contentType(MediaType.TEXT_PLAIN)
                .body("Failed to export exam right now. Please try again.".getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping("/department-dashboard")
    public String departmentDashboard(Model model, Principal principal) {
        String teacherEmail = principal != null ? principal.getName() : "";
        List<Subject> teacherSubjects = teacherEmail.isBlank()
            ? new ArrayList<>()
            : subjectRepository.findByTeacherEmail(teacherEmail);

        User currentTeacher = teacherEmail.isBlank() ? null : userRepository.findByEmail(teacherEmail).orElse(null);
        String teacherFullName = currentTeacher == null ? "" : (currentTeacher.getFullName() == null ? "" : currentTeacher.getFullName().trim());
        if (teacherFullName.isBlank() && !teacherEmail.isBlank()) {
            int atPos = teacherEmail.indexOf('@');
            teacherFullName = atPos > 0 ? teacherEmail.substring(0, atPos) : teacherEmail;
        }
        if (teacherFullName.isBlank()) {
            teacherFullName = "Teacher";
        }
        String departmentName = currentTeacher == null ? "" : (currentTeacher.getDepartmentName() == null ? "" : currentTeacher.getDepartmentName().trim());

        int departmentQuestionCount = 0;
        int departmentTeacherCount = 0;
        List<Subject> departmentSubjects = new ArrayList<>();
        if (!departmentName.isBlank()) {
            List<String> departmentTeacherEmails = userRepository.findAll().stream()
                .filter(user -> user != null && user.getRole() == User.Role.TEACHER)
                .filter(user -> departmentName.equalsIgnoreCase(user.getDepartmentName() == null ? "" : user.getDepartmentName().trim()))
                .map(User::getEmail)
                .filter(value -> value != null && !value.isBlank())
                .toList();

            departmentTeacherCount = departmentTeacherEmails.size();
            Set<String> departmentTeacherEmailSet = departmentTeacherEmails.stream()
                .map(value -> value.trim().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());

            departmentSubjects = subjectRepository.findAll().stream()
                .filter(subject -> subject != null && subject.getTeacherEmail() != null)
                .filter(subject -> departmentTeacherEmailSet.contains(subject.getTeacherEmail().trim().toLowerCase()))
                .sorted(Comparator.comparing(Subject::getSubjectName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Subject::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

            if (!departmentTeacherEmails.isEmpty()) {
                Set<String> normalizedDepartmentTeacherEmails = departmentTeacherEmails.stream()
                    .map(this::normalize)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toSet());

                departmentQuestionCount = (int) buildTemporaryQuestionBankItems(originalProcessedPaperRepository.findAll()).stream()
                    .map(QuestionBankItem::getSourceTeacherEmail)
                    .filter(value -> value != null && !value.isBlank())
                    .map(this::normalize)
                    .filter(normalizedDepartmentTeacherEmails::contains)
                    .count();
            }
        }

        model.addAttribute("subjects", teacherSubjects);
        model.addAttribute("teacherSubjectCount", teacherSubjects.size());
        model.addAttribute("departmentSubjects", departmentSubjects);
        model.addAttribute("teacherEmail", teacherEmail);
        model.addAttribute("teacherFullName", teacherFullName);
        model.addAttribute("departmentName", departmentName);
        model.addAttribute("departmentTeacherCount", departmentTeacherCount);
        model.addAttribute("departmentQuestionCount", departmentQuestionCount);
        model.addAttribute("departmentSubjectCount", departmentSubjects.size());
        return "homepage";
    }

    @GetMapping("/subjects")
    public String subjects(Model model, Principal principal) {
        String teacherEmail = principal != null ? principal.getName() : "";
        List<Subject> subjects = teacherEmail.isBlank()
            ? new ArrayList<>() 
            : subjectRepository.findByTeacherEmail(teacherEmail);

        Map<Long, Integer> enrollmentCountBySubject = new HashMap<>();
        for (Subject subject : subjects) {
            Long subjectId = subject.getId();
            if (subjectId == null) {
                continue;
            }
            int enrollmentCount = enrolledStudentRepository.findBySubjectId(subjectId).size();
            enrollmentCountBySubject.put(subjectId, enrollmentCount);
        }

        model.addAttribute("subjects", subjects);
        model.addAttribute("enrollmentCountBySubject", enrollmentCountBySubject);
        model.addAttribute("teacherEmail", teacherEmail);
        return "teacher-subjects";
    }

    @PostMapping("/create-subject")
    public String createSubject(@RequestParam("subjectName") String subjectName,
                                @RequestParam(name = "description", required = false) String description,
                                Principal principal,
                                RedirectAttributes redirectAttributes) {
        String teacherEmail = principal != null ? principal.getName() : "";
        if (teacherEmail.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to identify teacher account.");
            return "redirect:/teacher/subjects";
        }

        String normalizedSubjectName = subjectName == null ? "" : subjectName.trim();
        if (normalizedSubjectName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject name is required.");
            return "redirect:/teacher/subjects";
        }

        boolean duplicateSubject = subjectRepository.findByTeacherEmail(teacherEmail).stream()
            .map(Subject::getSubjectName)
            .filter(value -> value != null && !value.isBlank())
            .anyMatch(value -> value.trim().equalsIgnoreCase(normalizedSubjectName));
        if (duplicateSubject) {
            redirectAttributes.addFlashAttribute("errorMessage", "You already have a subject with that name.");
            return "redirect:/teacher/subjects";
        }

        String normalizedDescription = description == null ? "" : description.trim();
        Subject subject = new Subject(normalizedSubjectName,
            normalizedDescription.isBlank() ? null : normalizedDescription,
            teacherEmail);
        subjectRepository.save(subject);

        redirectAttributes.addFlashAttribute("successMessage", "Subject created successfully.");
        return "redirect:/teacher/subjects";
    }

    @PostMapping("/delete-subject")
    public String deleteSubject(@RequestParam("subjectId") Long subjectId,
                                Principal principal,
                                RedirectAttributes redirectAttributes) {
        String teacherEmail = principal != null ? principal.getName() : "";
        if (teacherEmail.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to identify teacher account.");
            return "redirect:/teacher/subjects";
        }

        Optional<Subject> subjectOpt = subjectRepository.findById(subjectId);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (subject.getTeacherEmail() == null || !subject.getTeacherEmail().equalsIgnoreCase(teacherEmail)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to delete this subject.");
            return "redirect:/teacher/subjects";
        }

        List<EnrolledStudent> enrollments = enrolledStudentRepository.findBySubjectId(subjectId);
        int removedEnrollments = enrollments.size();
        if (!enrollments.isEmpty()) {
            enrolledStudentRepository.deleteAll(enrollments);
        }
        subjectRepository.delete(subject);

        if (removedEnrollments > 0) {
            redirectAttributes.addFlashAttribute("successMessage",
                "Subject deleted successfully. " + removedEnrollments + " enrollment(s) removed.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Subject deleted successfully.");
        }
        return "redirect:/teacher/subjects";
    }

    @GetMapping("/students") 
    public String studentsAlias() {
        return "redirect:/teacher/subjects";
    }

    @GetMapping("/processed-papers")
    public String processedPapers(@RequestParam(name = "search", required = false) String search, Model model, Principal principal) {
        String teacherEmail = principal != null ? principal.getName() : "";
        List<OriginalProcessedPaper> papers = findTeacherProcessedPapers(teacherEmail);

        String normalizedSearch = normalize(search);
        List<Map<String, Object>> processedExams = new ArrayList<>();

        for (OriginalProcessedPaper paper : papers) {
            List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
            if (normalizeQuestionRowsInPlace(questions)) {
                paper.setOriginalQuestionsJson(gson.toJson(questions));
                originalProcessedPaperRepository.save(paper);
            }
            String subjectDisplay = paper.getSubject() == null || paper.getSubject().isBlank()
                ? "Unassigned"
                : paper.getSubject().trim();
            int questionCount = questions == null ? 0 : questions.size();
            String questionCountLabel = questionCount + " questions";
            String combinedText = normalize(paper.getExamName());

            if (!normalizedSearch.isEmpty() && !combinedText.contains(normalizedSearch)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            String ownerEmail = paper.getTeacherEmail() == null ? "" : paper.getTeacherEmail().trim();
            boolean isOwnedByCurrentTeacher = matchesTeacherOwner(teacherEmail, ownerEmail);
            row.put("examId", paper.getExamId());
            row.put("examName", paper.getExamName());
            row.put("subject", paper.getSubject());
            row.put("subjectDisplay", subjectDisplay);
            row.put("subjectBadge", subjectDisplay);
            row.put("activityType", paper.getActivityType());
            row.put("uploadedAt", paper.getProcessedAt());
            row.put("questions", questions);
            row.put("questionCount", questionCount);
            row.put("questionCountLabel", questionCountLabel);
            row.put("questionBadge", questionCountLabel);
            row.put("ownerEmail", ownerEmail);
            row.put("isOwnedByCurrentTeacher", isOwnedByCurrentTeacher);
            row.put("scopeLabel", isOwnedByCurrentTeacher ? "My Paper" : "Department Library");
            row.put("scopeClass", isOwnedByCurrentTeacher ? "processed-scope-owned" : "processed-scope-shared");
            processedExams.add(row);
        }

        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("processedExams", processedExams);
        model.addAttribute("totalProcessed", processedExams.size());
        return "teacher-processed-papers";
    }

    @PostMapping("/processed-papers")
    public String processedPapersPostRedirect(@RequestParam(name = "search", required = false) String search,
                                              RedirectAttributes redirectAttributes) {
        if (search != null && !search.isBlank()) {
            redirectAttributes.addAttribute("search", search);
        }
        return "redirect:/teacher/processed-papers";
    }

    @GetMapping("/question-bank")
    public String questionBankLegacyRedirect(@RequestParam(name = "search", required = false) String search,
                                             @RequestParam(name = "subject", required = false) String subject,
                                             RedirectAttributes redirectAttributes) {
        if (search != null && !search.isBlank()) {
            redirectAttributes.addAttribute("search", search);
        }
        if (subject != null && !subject.isBlank()) {
            redirectAttributes.addAttribute("subject", subject);
        }
        return "redirect:/teacher/department-dashboard/question-bank";
    }

    @GetMapping("/department-dashboard/question-bank")
    public String questionBank(@RequestParam(name = "search", required = false) String search,
                               @RequestParam(name = "subject", required = false) String subject,
                               Model model,
                               Principal principal) {
        String teacherEmail = principal != null ? principal.getName() : "";
        List<QuestionBankItem> allTemporaryItems = buildTemporaryQuestionBankItems(originalProcessedPaperRepository.findAll());
        List<QuestionBankItem> sourceItems = allTemporaryItems.stream()
            .filter(item -> subject == null || subject.isBlank()
                || subject.trim().equalsIgnoreCase(item.getSubject() == null ? "" : item.getSubject().trim()))
            .sorted(Comparator.comparing(QuestionBankItem::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(QuestionBankItem::getQuestionOrder, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        String normalizedSearch = normalize(search);
        List<Map<String, Object>> questionBankRows = new ArrayList<>();
        TreeSet<String> subjectOptions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, User> uploaderProfilesByEmail = new HashMap<>();

        for (QuestionBankItem item : allTemporaryItems) {
            if (item.getSubject() != null && !item.getSubject().isBlank()) {
                subjectOptions.add(item.getSubject().trim());
            }
        }

        List<String> uploaderEmails = sourceItems.stream()
            .map(QuestionBankItem::getSourceTeacherEmail)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
        if (!uploaderEmails.isEmpty()) {
            for (User user : userRepository.findByEmailIn(uploaderEmails)) {
                if (user != null && user.getEmail() != null) {
                    uploaderProfilesByEmail.put(user.getEmail().trim().toLowerCase(), user);
                }
            }
        }

        for (QuestionBankItem item : sourceItems) {
            String preview = toPlainQuestionText(item.getQuestionText());
            String uploaderEmail = item.getSourceTeacherEmail() == null ? "" : item.getSourceTeacherEmail().trim();
            User uploader = uploaderProfilesByEmail.get(uploaderEmail.toLowerCase());
            String uploaderSchool = uploader == null ? "" : (uploader.getSchoolName() == null ? "" : uploader.getSchoolName().trim());
            String uploaderCampus = uploader == null ? "" : (uploader.getCampusName() == null ? "" : uploader.getCampusName().trim());
            String uploaderDepartment = uploader == null ? "" : (uploader.getDepartmentName() == null ? "" : uploader.getDepartmentName().trim());

            String searchable = normalize(preview)
                + " " + normalize(item.getSourceExamName())
                + " " + normalize(item.getSubject())
                + " " + normalize(item.getDifficulty())
                + " " + normalize(item.getActivityType())
                + " " + normalize(item.getSourceTeacherEmail())
                + " " + normalize(uploaderSchool)
                + " " + normalize(uploaderCampus)
                + " " + normalize(uploaderDepartment);

            if (!normalizedSearch.isEmpty() && !searchable.contains(normalizedSearch)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("id", buildTemporaryQuestionBankItemId(item));
            row.put("questionPreview", preview);
            row.put("subject", item.getSubject());
            row.put("activityType", item.getActivityType());
            row.put("difficulty", item.getDifficulty());
            row.put("sourceExamName", item.getSourceExamName());
            row.put("sourceTeacherEmail", item.getSourceTeacherEmail());
            row.put("sourceTeacherSchool", uploaderSchool);
            row.put("sourceTeacherCampus", uploaderCampus);
            row.put("sourceTeacherDepartment", uploaderDepartment);
            row.put("sourceExamId", item.getSourceExamId());
            row.put("choiceCount", parseStringListJson(item.getChoicesJson()).size());
            row.put("createdAt", item.getCreatedAt());
            questionBankRows.add(row);
        }

        Map<String, Map<String, Object>> subjectGroups = new LinkedHashMap<>();
        for (Map<String, Object> row : questionBankRows) {
            String subjectName = String.valueOf(row.getOrDefault("subject", "")).trim();
            if (subjectName.isBlank()) {
                subjectName = "Uncategorized";
            }

            Map<String, Object> subjectGroup = subjectGroups.get(subjectName);
            if (subjectGroup == null) {
                subjectGroup = new LinkedHashMap<>();
                subjectGroup.put("subjectName", subjectName);
                subjectGroup.put("subjectQuestionCount", 0);
                subjectGroup.put("paperCount", 0);
                subjectGroup.put("paperGroups", new ArrayList<Map<String, Object>>());
                subjectGroups.put(subjectName, subjectGroup);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> paperGroups = (List<Map<String, Object>>) subjectGroup.get("paperGroups");

            String paperName = String.valueOf(row.getOrDefault("sourceExamName", "")).trim();
            if (paperName.isBlank()) {
                paperName = "Unknown Paper";
            }
            String paperActivity = String.valueOf(row.getOrDefault("activityType", "")).trim();
            String paperUploader = String.valueOf(row.getOrDefault("sourceTeacherEmail", "")).trim();
            String paperExamId = String.valueOf(row.getOrDefault("sourceExamId", "")).trim();

            String paperKey = paperName + "|" + paperActivity + "|" + paperUploader + "|" + paperExamId;
            Map<String, Object> paperGroup = null;
            for (Map<String, Object> existing : paperGroups) {
                if (paperKey.equals(existing.get("paperKey"))) {
                    paperGroup = existing;
                    break;
                }
            }

            if (paperGroup == null) {
                paperGroup = new LinkedHashMap<>();
                paperGroup.put("paperKey", paperKey);
                paperGroup.put("paperName", paperName);
                paperGroup.put("activityType", paperActivity.isBlank() ? "Exam" : paperActivity);
                paperGroup.put("uploadedBy", paperUploader);
                paperGroup.put("school", row.getOrDefault("sourceTeacherSchool", ""));
                paperGroup.put("campus", row.getOrDefault("sourceTeacherCampus", ""));
                paperGroup.put("department", row.getOrDefault("sourceTeacherDepartment", ""));
                paperGroup.put("questionCount", 0);
                paperGroup.put("questions", new ArrayList<Map<String, Object>>());
                paperGroups.add(paperGroup);

                int paperCount = ((Integer) subjectGroup.get("paperCount"));
                subjectGroup.put("paperCount", paperCount + 1);
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> questions = (List<Map<String, Object>>) paperGroup.get("questions");
            questions.add(row);
            int paperQuestionCount = ((Integer) paperGroup.get("questionCount"));
            paperGroup.put("questionCount", paperQuestionCount + 1);

            int subjectQuestionCount = ((Integer) subjectGroup.get("subjectQuestionCount"));
            subjectGroup.put("subjectQuestionCount", subjectQuestionCount + 1);
        }

        model.addAttribute("teacherEmail", teacherEmail);
        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("selectedSubject", subject == null ? "" : subject);
        model.addAttribute("subjectOptions", new ArrayList<>(subjectOptions));
        model.addAttribute("questionBankItems", questionBankRows);
        model.addAttribute("questionBankGrouped", new ArrayList<>(subjectGroups.values()));
        model.addAttribute("totalQuestionBank", questionBankRows.size());
        model.addAttribute("departmentName", userRepository.findByEmail(teacherEmail)
            .map(User::getDepartmentName)
            .orElse(""));
        return "teacher-question-bank";
    }

    @PostMapping({"/question-bank/create-exam", "/department-dashboard/question-bank/create-exam"})
    public String createExamFromQuestionBank(@RequestParam(name = "questionIds", required = false) List<Long> questionIds,
                                             @RequestParam("examName") String examName,
                                             @RequestParam("subject") String subject,
                                             @RequestParam("activityType") String activityType,
                                             Principal principal,
                                             RedirectAttributes redirectAttributes) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to identify teacher account.");
            return "redirect:/teacher/department-dashboard/question-bank";
        }

        if (questionIds == null || questionIds.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Select at least one saved question.");
            return "redirect:/teacher/department-dashboard/question-bank";
        }

        String normalizedExamName = examName == null ? "" : examName.trim();
        if (normalizedExamName.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Exam name is required.");
            return "redirect:/teacher/department-dashboard/question-bank";
        }
        String uniqueExamName = ensureUniqueExamNameForTeacher(principal.getName(), normalizedExamName);

        Set<Long> selectedIds = new LinkedHashSet<>(questionIds);
        Map<Long, QuestionBankItem> itemsById = new LinkedHashMap<>();
        for (QuestionBankItem item : buildTemporaryQuestionBankItems(originalProcessedPaperRepository.findAll())) {
            long tempId = buildTemporaryQuestionBankItemId(item);
            if (selectedIds.contains(tempId)) {
                itemsById.put(tempId, item);
            }
        }

        List<Map<String, Object>> questions = new ArrayList<>();
        Map<String, String> difficulties = new LinkedHashMap<>();
        Map<String, String> answerKey = new LinkedHashMap<>();
        QuestionBankItem firstSelectedItem = null;

        for (Long questionId : questionIds) {
            QuestionBankItem item = itemsById.get(questionId);
            if (item == null) {
                continue;
            }

            if (firstSelectedItem == null) {
                firstSelectedItem = item;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("question", normalizeQuestionHtml(item.getQuestionText()));
            row.put("choices", parseStringListJson(item.getChoicesJson()));
            questions.add(row);

            String key = String.valueOf(questions.size());
            difficulties.put(key, normalizeDifficulty(item.getDifficulty()));
            answerKey.put(key, item.getAnswerText() == null ? "" : item.getAnswerText());
        }

        if (questions.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No reusable questions were found for the selected items.");
            return "redirect:/teacher/department-dashboard/question-bank";
        }

        String normalizedSubject = (subject == null || subject.isBlank())
            ? Optional.ofNullable(firstSelectedItem).map(QuestionBankItem::getSubject).orElse("General")
            : subject.trim();
        String normalizedActivityType = (activityType == null || activityType.isBlank())
            ? Optional.ofNullable(firstSelectedItem).map(QuestionBankItem::getActivityType).orElse("Exam")
            : activityType.trim();

        String examId = "EXAM_" + UUID.randomUUID().toString().replace("-", "");
        OriginalProcessedPaper paper = new OriginalProcessedPaper(
            examId,
            principal.getName(),
            uniqueExamName,
            normalizedSubject,
            normalizedActivityType,
            "question-bank-mix",
            gson.toJson(questions),
            gson.toJson(difficulties),
            gson.toJson(answerKey)
        );

        originalProcessedPaperRepository.save(paper);
        syncQuestionBankForPaper(paper, questions, difficulties, answerKey);
        if (sameText(uniqueExamName, normalizedExamName)) {
            redirectAttributes.addFlashAttribute("successMessage", "Mixed exam created from the question bank.");
        } else {
            redirectAttributes.addFlashAttribute(
                "successMessage",
                "Mixed exam created from the question bank as \"" + uniqueExamName + "\" (name already existed)."
            );
        }
        return "redirect:/teacher/manage-questions/" + examId;
    }

    @PostMapping("/process-exams-upload")
    public String processExamsUpload(@RequestParam("examCreated") MultipartFile examCreated,
                                     @RequestParam(value = "quizName", required = false) String quizName,
                                     @RequestParam("subject") String subject,
                                     @RequestParam("activityType") String activityType,
                                     Principal principal,
                                     RedirectAttributes redirectAttributes) {
        try {
            if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
                redirectAttributes.addFlashAttribute("errorMessage", "Unable to identify teacher account.");
                return "redirect:/teacher/homepage";
            }

            if (examCreated == null || examCreated.isEmpty()) {
                redirectAttributes.addFlashAttribute("errorMessage", "Please upload an exam file (PDF or CSV).");
                return "redirect:/teacher/homepage";
            }

            List<Map<String, Object>> questions = parseExamFile(examCreated);
            Map<String, String> answerKeyMap = parseAnswerKey(questions, null);
            Map<String, String> difficulties = buildDifficultiesFromQuestions(questions);

            String originalFilename = examCreated.getOriginalFilename();
            String requestedExamName = deriveExamName(quizName, originalFilename);
            String generatedExamName = ensureUniqueExamNameForTeacher(principal.getName(), requestedExamName);
            String examId = "EXAM_" + UUID.randomUUID().toString().replace("-", "");

            OriginalProcessedPaper paper = new OriginalProcessedPaper(
                examId,
                principal.getName(),
                generatedExamName,
                subject,
                activityType,
                originalFilename,
                gson.toJson(questions),
                gson.toJson(difficulties),
                gson.toJson(answerKeyMap)
            );

            UploadStorageService.StoredFile storedExamFile = uploadStorageService.store(
                "processed-exams",
                principal.getName(),
                examCreated
            );
            paper.setSourceFilename(storedExamFile.originalFilename());
            paper.setSourceFilePath(storedExamFile.relativePath());
            paper.setSourceFileChecksum(storedExamFile.checksum());
            paper.setSourceFileSize(storedExamFile.size());

            originalProcessedPaperRepository.save(paper);
            syncQuestionBankForPaper(paper, questions, difficulties, answerKeyMap);
            if (sameText(generatedExamName, requestedExamName)) {
                redirectAttributes.addFlashAttribute("successMessage", "Exam processed successfully.");
            } else {
                redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Exam processed successfully as \"" + generatedExamName + "\" (name already existed)."
                );
            }
            return "redirect:/teacher/processed-papers";
        } catch (Exception exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to process exam: " + exception.getMessage());
            return "redirect:/teacher/homepage";
        }
    }

    @GetMapping("/processed-papers/{examId}")
    public String processedPaperDetail(@PathVariable("examId") String examId,
                                       @RequestParam(name = "questionSearch", required = false) String questionSearch,
                                       Model model,
                                       Principal principal) {
        String currentTeacherEmail = principal == null ? "" : (principal.getName() == null ? "" : principal.getName().trim());
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        boolean isOwnedByCurrentTeacher = matchesTeacherOwner(currentTeacherEmail, paper.getTeacherEmail());
        if (!isOwnedByCurrentTeacher) {
            User currentTeacher = currentTeacherEmail.isBlank() ? null : userRepository.findByEmail(currentTeacherEmail).orElse(null);
            String currentDepartment = currentTeacher == null ? "" : (currentTeacher.getDepartmentName() == null ? "" : currentTeacher.getDepartmentName().trim());

            User owner = paper.getTeacherEmail() == null || paper.getTeacherEmail().isBlank()
                ? null
                : userRepository.findByEmail(paper.getTeacherEmail().trim()).orElse(null);
            String sourceDepartment = paper.getDepartmentName() == null ? "" : paper.getDepartmentName().trim();
            if (sourceDepartment.isBlank() && owner != null && owner.getDepartmentName() != null) {
                sourceDepartment = owner.getDepartmentName().trim();
            }

            if (!currentDepartment.isBlank()
                && !sourceDepartment.isBlank()
                && !currentDepartment.equalsIgnoreCase(sourceDepartment)) {
                return "redirect:/teacher/processed-papers";
            }
        }

        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        if (normalizeQuestionRowsInPlace(questions)) {
            paper.setOriginalQuestionsJson(gson.toJson(questions));
            originalProcessedPaperRepository.save(paper);
        }
        Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());
        Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());
        String normalizedSearch = normalize(questionSearch);

        List<Map<String, Object>> questionRows = new ArrayList<>();
        int displayNumber = 1;
        for (int index = 0; index < questions.size(); index++) {
            Map<String, Object> question = questions.get(index);
            if (question == null) {
                continue;
            }
            String number = String.valueOf(index + 1);
            String answerText = answerKey.getOrDefault(number, "Not Set");
            String difficulty = difficulties.getOrDefault(number, "Medium");
            String questionText = resolveQuestionDisplay(question, difficulty, answerText);
            String questionPreview = toPlainQuestionText(questionText);

            if (questionPreview.isBlank()) {
                continue;
            }

            String searchable = normalize(questionPreview) + " " + normalize(answerText);
            if (!normalizedSearch.isEmpty() && !searchable.contains(normalizedSearch)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("number", displayNumber++);
            row.put("question", questionPreview);
            row.put("answer", answerText);
            row.put("difficulty", difficulty);
            questionRows.add(row);
        }

        Map<String, Object> exam = new HashMap<>();
        exam.put("examId", paper.getExamId());
        exam.put("examName", paper.getExamName());
        exam.put("subject", paper.getSubject());
        exam.put("activityType", paper.getActivityType());
        exam.put("uploadedAt", paper.getProcessedAt());
        exam.put("ownerEmail", paper.getTeacherEmail() == null ? "" : paper.getTeacherEmail().trim());

        model.addAttribute("exam", exam);
        model.addAttribute("isOwnedByCurrentTeacher", isOwnedByCurrentTeacher);
        model.addAttribute("questionRows", questionRows);
        model.addAttribute("questionSearch", questionSearch == null ? "" : questionSearch);
        return "teacher-processed-paper-detail";
    }

    @PostMapping("/processed-papers/{examId}")
    public String processedPaperDetailPostRedirect(@PathVariable("examId") String examId,
                                                   @RequestParam(name = "questionSearch", required = false) String questionSearch,
                                                   RedirectAttributes redirectAttributes) {
        if (questionSearch != null && !questionSearch.isBlank()) {
            redirectAttributes.addAttribute("questionSearch", questionSearch);
        }
        return "redirect:/teacher/processed-papers/" + examId;
    }

    @PostMapping("/processed-papers/{examId}/pull")
    public String pullProcessedPaper(@PathVariable("examId") String examId,
                                     Principal principal,
                                     RedirectAttributes redirectAttributes) {
        String currentTeacherEmail = principal == null ? "" : (principal.getName() == null ? "" : principal.getName().trim());
        if (currentTeacherEmail.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to identify teacher account.");
            return "redirect:/teacher/processed-papers";
        }

        Optional<OriginalProcessedPaper> sourceOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (sourceOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Processed paper not found.");
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper source = sourceOpt.get();
        if (matchesTeacherOwner(currentTeacherEmail, source.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("successMessage", "This paper is already in your workspace.");
            return "redirect:/teacher/manage-questions/" + source.getExamId();
        }

        User currentTeacher = userRepository.findByEmail(currentTeacherEmail).orElse(null);
        String currentDepartment = currentTeacher == null ? "" : (currentTeacher.getDepartmentName() == null ? "" : currentTeacher.getDepartmentName().trim());

        User owner = source.getTeacherEmail() == null || source.getTeacherEmail().isBlank()
            ? null
            : userRepository.findByEmail(source.getTeacherEmail().trim()).orElse(null);

        String sourceDepartment = source.getDepartmentName() == null ? "" : source.getDepartmentName().trim();
        if (sourceDepartment.isBlank() && owner != null && owner.getDepartmentName() != null) {
            sourceDepartment = owner.getDepartmentName().trim();
        }

        if (!currentDepartment.isBlank()
            && !sourceDepartment.isBlank()
            && !currentDepartment.equalsIgnoreCase(sourceDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can only pull papers from your department library.");
            return "redirect:/teacher/processed-papers";
        }

        String copiedExamId = "EXAM_" + UUID.randomUUID().toString().replace("-", "");
        String clonedExamName = ensureUniqueExamNameForTeacher(currentTeacherEmail, source.getExamName());

        OriginalProcessedPaper clone = new OriginalProcessedPaper(
            copiedExamId,
            currentTeacherEmail,
            clonedExamName,
            source.getSubject(),
            source.getActivityType(),
            source.getSourceFilename(),
            source.getOriginalQuestionsJson(),
            source.getDifficultiesJson(),
            source.getAnswerKeyJson()
        );

        clone.setDepartmentName(currentDepartment.isBlank() ? sourceDepartment : currentDepartment);
        clone.setSourceFilePath(source.getSourceFilePath());
        clone.setSourceFileChecksum(source.getSourceFileChecksum());
        clone.setSourceFileSize(source.getSourceFileSize());
        clone.setAnswerKeyFilename(source.getAnswerKeyFilename());
        clone.setAnswerKeyFilePath(source.getAnswerKeyFilePath());
        clone.setAnswerKeyFileChecksum(source.getAnswerKeyFileChecksum());
        clone.setAnswerKeyFileSize(source.getAnswerKeyFileSize());

        originalProcessedPaperRepository.save(clone);

        List<Map<String, Object>> clonedQuestions = parseQuestionsJson(clone.getOriginalQuestionsJson());
        Map<String, String> clonedDifficulties = parseSimpleMapJson(clone.getDifficultiesJson());
        Map<String, String> clonedAnswerKey = parseSimpleMapJson(clone.getAnswerKeyJson());
        syncQuestionBankForPaper(clone, clonedQuestions, clonedDifficulties, clonedAnswerKey);

        redirectAttributes.addFlashAttribute(
            "successMessage",
            "Paper pulled successfully as \"" + clonedExamName + "\". You can now edit and add questions."
        );
        return "redirect:/teacher/manage-questions/" + copiedExamId;
    }

    @PostMapping("/processed-papers/{examId}/delete")
    public String deleteProcessedPaper(@PathVariable("examId") String examId,
                                       Principal principal,
                                       RedirectAttributes redirectAttributes) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Processed paper not found.");
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to delete this exam.");
            return "redirect:/teacher/processed-papers";
        }

        originalProcessedPaperRepository.delete(paper);
        redirectAttributes.addFlashAttribute("successMessage", "Processed paper deleted successfully.");
        return "redirect:/teacher/processed-papers";
    }

    @PostMapping("/processed-papers/{examId}/repair-questions")
    public String repairProcessedPaperQuestions(@PathVariable("examId") String examId,
                                                Principal principal,
                                                RedirectAttributes redirectAttributes) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Processed paper not found.");
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this exam.");
            return "redirect:/teacher/processed-papers";
        }

        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());
        Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());

        int repaired = 0;
        for (int index = 0; index < questions.size(); index++) {
            Map<String, Object> row = questions.get(index);
            String key = String.valueOf(index + 1);
            String current = String.valueOf(row.getOrDefault("question", "")).trim();
            String resolved = resolveQuestionCandidate(
                row,
                difficulties.getOrDefault(key, "Medium"),
                answerKey.getOrDefault(key, ""),
                false
            );

            if (!resolved.isBlank() && !resolved.equals(current)) {
                row.put("question", resolved);
                repaired++;
            }
        }

        if (repaired > 0) {
            paper.setOriginalQuestionsJson(gson.toJson(questions));
            originalProcessedPaperRepository.save(paper);
            syncQuestionBankForPaper(paper, questions, difficulties, answerKey);
            redirectAttributes.addFlashAttribute("successMessage", repaired + " question row(s) repaired.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "No malformed question rows found.");
        }

        return "redirect:/teacher/processed-papers/" + examId;
    }

    @GetMapping("/manage-questions/{examId}")
    public String manageQuestions(@PathVariable("examId") String examId,
                                  @RequestParam(name = "returnTo", required = false) String returnTo,
                                  Model model,
                                  Principal principal) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        String teacherEmail = principal != null ? principal.getName() : "";
        if (!teacherEmail.isBlank() && !teacherEmail.equalsIgnoreCase(paper.getTeacherEmail())) {
            return "redirect:/teacher/processed-papers";
        }

        List<Map<String, Object>> questionRows = parseQuestionsJson(paper.getOriginalQuestionsJson());
        if (normalizeQuestionRowsInPlace(questionRows)) {
            paper.setOriginalQuestionsJson(gson.toJson(questionRows));
            originalProcessedPaperRepository.save(paper);
        }
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());
        Map<String, String> difficultiesMap = parseSimpleMapJson(paper.getDifficultiesJson());

        List<String> questions = new ArrayList<>();
        for (int index = 0; index < questionRows.size(); index++) {
            String key = String.valueOf(index + 1);
            String difficulty = difficultiesMap.getOrDefault(key, "Medium");
            String answer = answerKeyMap.getOrDefault(key, "");
            questions.add(resolveQuestionDisplay(questionRows.get(index), difficulty, answer));
        }

        List<String> difficulties = new ArrayList<>();
        for (int index = 0; index < questions.size(); index++) {
            difficulties.add(difficultiesMap.getOrDefault(String.valueOf(index + 1), "Medium"));
        }

        List<String> answerKey = new ArrayList<>();
        for (int index = 0; index < questions.size(); index++) {
            answerKey.add(answerKeyMap.getOrDefault(String.valueOf(index + 1), ""));
        }

        List<String> reversedQuestions = new ArrayList<>();
        List<String> reversedDifficulties = new ArrayList<>();
        List<String> reversedAnswerKey = new ArrayList<>();
        List<String> reversedQuestionDisplay = new ArrayList<>();
        List<Integer> questionOriginalIndexes = new ArrayList<>();
        for (int index = questions.size() - 1; index >= 0; index--) {
            reversedQuestions.add(questions.get(index));
            reversedDifficulties.add(difficulties.get(index));
            reversedAnswerKey.add(answerKey.get(index));
            reversedQuestionDisplay.add(questions.get(index));
            questionOriginalIndexes.add(index);
        }

        Map<String, Object> exam = new HashMap<>();
        exam.put("examId", paper.getExamId());
        exam.put("examName", paper.getExamName());
        exam.put("subject", paper.getSubject());
        exam.put("activityType", paper.getActivityType());
        exam.put("questions", reversedQuestions);
        exam.put("difficulties", reversedDifficulties);
        exam.put("answerKey", reversedAnswerKey);

        model.addAttribute("exam", exam);
        model.addAttribute("questionDisplay", reversedQuestionDisplay);
        model.addAttribute("questionOriginalIndexes", questionOriginalIndexes);
        model.addAttribute("returnTo", returnTo == null || returnTo.isBlank() ? "/teacher/processed-papers" : returnTo);
        return "manage-questions";
    }

    @PostMapping("/add-question")
    public String addQuestion(@RequestParam("examId") String examId,
                              @RequestParam("questionText") String questionText,
                              @RequestParam(name = "questionType", required = false) String questionType,
                              @RequestParam(name = "choicesText", required = false) String choicesText,
                              @RequestParam(name = "answer", required = false) String answer,
                              @RequestParam("difficulty") String difficulty,
                              @RequestParam(name = "returnTo", required = false) String returnTo,
                              Principal principal,
                              RedirectAttributes redirectAttributes) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Exam not found.");
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this exam.");
            return "redirect:/teacher/processed-papers";
        }

        try {
            List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
            Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());
            Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());

            Map<String, Object> newQuestion = new HashMap<>();
            String normalizedQuestion = normalizeQuestionHtml(questionText);
            boolean openEnded = "OPEN_ENDED".equalsIgnoreCase(questionType);

            if (openEnded && !normalizedQuestion.startsWith("[TEXT_INPUT]")) {
                normalizedQuestion = "[TEXT_INPUT]" + normalizedQuestion;
            }

            newQuestion.put("question", normalizedQuestion);

            List<String> choices = new ArrayList<>();
            if (!openEnded && choicesText != null && !choicesText.isBlank()) {
                for (String line : choicesText.split("\\r?\\n")) {
                    String normalizedChoice = normalizeMathSymbols(line);
                    if (!normalizedChoice.isBlank()) {
                        choices.add(normalizedChoice);
                    }
                }
            }
            newQuestion.put("choices", choices);
            applyDefaultItemParameters(newQuestion, normalizeDifficulty(difficulty));

            int newNumber = questions.size() + 1;
            String key = String.valueOf(newNumber);
            questions.add(newQuestion);
            difficulties.put(key, normalizeMathSymbols(difficulty).isBlank() ? "Medium" : normalizeMathSymbols(difficulty));
            String normalizedAnswer = normalizeMathSymbols(answer);
            answerKey.put(key, openEnded
                ? (normalizedAnswer.isBlank() ? "MANUAL_GRADE" : normalizedAnswer)
                : normalizedAnswer);

            paper.setOriginalQuestionsJson(gson.toJson(questions));
            paper.setDifficultiesJson(gson.toJson(difficulties));
            paper.setAnswerKeyJson(gson.toJson(answerKey));
            originalProcessedPaperRepository.save(paper);

            try {
                syncQuestionBankForPaper(paper, questions, difficulties, answerKey);
            } catch (Exception syncException) {
                LOGGER.error("Question bank sync failed after adding question. examId={}", examId, syncException);
                redirectAttributes.addFlashAttribute("warningMessage",
                    "Question was added, but question bank sync encountered an issue.");
            }

            redirectAttributes.addFlashAttribute("successMessage", "Question added successfully.");
        } catch (Exception exception) {
            LOGGER.error("Failed to add question. examId={}", examId, exception);
            redirectAttributes.addFlashAttribute("errorMessage",
                "Unable to add question due to invalid pasted formatting. Please paste as plain text and try again.");
        }
        return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
    }

    @PostMapping("/edit-question")
    public String editQuestion(@RequestParam("examId") String examId,
                               @RequestParam("questionIndex") Integer questionIndex,
                               @RequestParam("questionText") String questionText,
                               @RequestParam(name = "questionType", required = false) String questionType,
                               @RequestParam(name = "answer", required = false) String answer,
                               @RequestParam("difficulty") String difficulty,
                               @RequestParam(name = "returnTo", required = false) String returnTo,
                               Principal principal,
                               RedirectAttributes redirectAttributes) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Exam not found.");
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this exam.");
            return "redirect:/teacher/processed-papers";
        }

        try {
            List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
            if (questionIndex == null || questionIndex < 0 || questionIndex >= questions.size()) {
                redirectAttributes.addFlashAttribute("errorMessage", "Invalid question index.");
                return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
            }

            Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());
            Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());

            boolean openEnded = "OPEN_ENDED".equalsIgnoreCase(questionType);
            String normalizedQuestion = normalizeQuestionHtml(questionText);
            if (openEnded && !normalizedQuestion.startsWith("[TEXT_INPUT]")) {
                normalizedQuestion = "[TEXT_INPUT]" + normalizedQuestion;
            }

            Map<String, Object> target = questions.get(questionIndex);
            target.put("question", normalizedQuestion);
            applyDefaultItemParameters(target, normalizeDifficulty(difficulty));

            String key = String.valueOf(questionIndex + 1);
            difficulties.put(key, normalizeMathSymbols(difficulty).isBlank() ? "Medium" : normalizeMathSymbols(difficulty));
            String normalizedAnswer = normalizeMathSymbols(answer);
            answerKey.put(key, openEnded
                ? (normalizedAnswer.isBlank() ? "MANUAL_GRADE" : normalizedAnswer)
                : normalizedAnswer);

            paper.setOriginalQuestionsJson(gson.toJson(questions));
            paper.setDifficultiesJson(gson.toJson(difficulties));
            paper.setAnswerKeyJson(gson.toJson(answerKey));
            originalProcessedPaperRepository.save(paper);

            try {
                syncQuestionBankForPaper(paper, questions, difficulties, answerKey);
            } catch (Exception syncException) {
                LOGGER.error("Question bank sync failed after editing question. examId={}, questionIndex={}",
                    examId,
                    questionIndex,
                    syncException);
                redirectAttributes.addFlashAttribute("warningMessage",
                    "Question was updated, but question bank sync encountered an issue.");
            }

            redirectAttributes.addFlashAttribute("successMessage", "Question updated successfully.");
        } catch (Exception exception) {
            LOGGER.error("Failed to edit question. examId={}, questionIndex={}", examId, questionIndex, exception);
            redirectAttributes.addFlashAttribute("errorMessage",
                "Unable to update question due to invalid pasted formatting. Please paste as plain text and try again.");
        }
        return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
    }

    @PostMapping("/delete-question")
    public String deleteQuestion(@RequestParam("examId") String examId,
                                 @RequestParam("questionIndex") Integer questionIndex,
                                 @RequestParam(name = "returnTo", required = false) String returnTo,
                                 Principal principal,
                                 RedirectAttributes redirectAttributes) {
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Exam not found.");
            return "redirect:/teacher/processed-papers";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this exam.");
            return "redirect:/teacher/processed-papers";
        }

        try {
            List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
            if (questionIndex == null || questionIndex < 0 || questionIndex >= questions.size()) {
                redirectAttributes.addFlashAttribute("errorMessage", "Invalid question index.");
                return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
            }

            questions.remove((int) questionIndex);
            Map<String, String> difficulties = reindexMap(parseSimpleMapJson(paper.getDifficultiesJson()), questions.size());
            Map<String, String> answerKey = reindexMap(parseSimpleMapJson(paper.getAnswerKeyJson()), questions.size());

            paper.setOriginalQuestionsJson(gson.toJson(questions));
            paper.setDifficultiesJson(gson.toJson(difficulties));
            paper.setAnswerKeyJson(gson.toJson(answerKey));
            originalProcessedPaperRepository.save(paper);

            try {
                syncQuestionBankForPaper(paper, questions, difficulties, answerKey);
            } catch (Exception syncException) {
                LOGGER.error("Question bank sync failed after deleting question. examId={}, questionIndex={}",
                    examId,
                    questionIndex,
                    syncException);
                redirectAttributes.addFlashAttribute("warningMessage",
                    "Question was deleted, but question bank sync encountered an issue.");
            }

            redirectAttributes.addFlashAttribute("successMessage", "Question deleted successfully.");
        } catch (Exception exception) {
            LOGGER.error("Failed to delete question. examId={}, questionIndex={}", examId, questionIndex, exception);
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to delete question right now. Please try again.");
        }
        return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
    }

    @GetMapping("/subject-classroom/{id}")
    public String subjectClassroom(@PathVariable("id") Long id, Model model, Principal principal) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        String teacherEmail = principal != null ? principal.getName() : "";
        if (!teacherEmail.isBlank() && !teacherEmail.equalsIgnoreCase(subject.getTeacherEmail())) {
            return "redirect:/teacher/subjects";
        }

        List<EnrolledStudent> enrolledStudents = enrolledStudentRepository.findBySubjectId(id);
        List<OriginalProcessedPaper> teacherPapers = findTeacherProcessedPapers(teacherEmail);

        List<Map<String, Object>> uploadedExams = new ArrayList<>();
        for (OriginalProcessedPaper paper : teacherPapers) {
            if (paper.getSubject() != null && paper.getSubject().equalsIgnoreCase(subject.getSubjectName())) {
                Map<String, Object> exam = new HashMap<>();
                exam.put("examId", paper.getExamId());
                exam.put("examName", paper.getExamName());
                exam.put("activityType", paper.getActivityType());
                exam.put("questions", parseQuestionsJson(paper.getOriginalQuestionsJson()));
                uploadedExams.add(exam);
            }
        }

        int totalSubmissions = examSubmissionRepository.findAll().stream()
            .filter(sub -> sub.getSubject() != null && sub.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .toList()
            .size();
        int gradedCount = (int) examSubmissionRepository.findAll().stream()
            .filter(sub -> sub.getSubject() != null && sub.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .filter(sub -> sub.isGraded() != null && sub.isGraded())
            .count();

        Map<String, Object> classroomStats = new HashMap<>();
        classroomStats.put("totalSubmissions", totalSubmissions);
        classroomStats.put("gradedCount", gradedCount);
        classroomStats.put("pendingCount", Math.max(0, totalSubmissions - gradedCount));

        model.addAttribute("subject", subject);
        model.addAttribute("enrolledStudents", enrolledStudents);
        model.addAttribute("uploadedExams", uploadedExams);
        model.addAttribute("classroomStats", classroomStats);
        List<User> allStudents = userRepository.findAll().stream()
            .filter(user -> user != null && user.getRole() == User.Role.STUDENT)
            .toList();

        String currentTeacherDepartment = userRepository.findByEmail(teacherEmail)
            .map(User::getDepartmentName)
            .map(String::trim)
            .orElse("");
        List<String> currentTeacherPrograms = AcademicCatalog.programsForDepartment(currentTeacherDepartment);

        model.addAttribute("allStudents", allStudents);
        model.addAttribute("currentTeacherDepartment", currentTeacherDepartment);
        model.addAttribute("currentTeacherPrograms", currentTeacherPrograms);
        model.addAttribute("departmentOptions", DEPARTMENTS);

        List<DistributedExam> distributedExams = distributedExamRepository.findAll().stream()
            .filter(item -> item.getSubject() != null && item.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .toList();

        List<Map<String, Object>> quizDistributionSummary;
        int distributedSubmittedCount;
        int distributedNotSubmittedCount;
        try {
            quizDistributionSummary = buildQuizDistributionSummary(distributedExams);
            distributedSubmittedCount = quizDistributionSummary.stream()
                .mapToInt(item -> (int) item.getOrDefault("submittedCount", 0))
                .sum();
            distributedNotSubmittedCount = quizDistributionSummary.stream()
                .mapToInt(item -> (int) item.getOrDefault("notSubmittedCount", 0))
                .sum();
        } catch (Exception exception) {
            quizDistributionSummary = new ArrayList<>();
            distributedSubmittedCount = 0;
            distributedNotSubmittedCount = 0;
            model.addAttribute("errorMessage", "Unable to load distribution tracker right now.");
        }

        model.addAttribute("distributionTracker", quizDistributionSummary);
        model.addAttribute("quizDistributionSummary", quizDistributionSummary);
        model.addAttribute("classroomStudentSummary", new ArrayList<>());
        model.addAttribute("distributedSubmittedCount", distributedSubmittedCount);
        model.addAttribute("distributedNotSubmittedCount", distributedNotSubmittedCount);
        return "subject-classroom";
    }

    @PostMapping("/enroll-student")
    public String enrollStudent(@RequestParam("subjectId") Long subjectId,
                                @RequestParam(name = "studentEmail", required = false) String studentEmail,
                                @RequestParam(name = "selectedStudents", required = false) List<String> selectedStudents,
                                Principal principal,
                                RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(subjectId);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this subject.");
            return "redirect:/teacher/subjects";
        }

        Set<String> normalizedEmails = new LinkedHashSet<>();
        if (selectedStudents != null) {
            selectedStudents.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .forEach(normalizedEmails::add);
        }
        if (normalizedEmails.isEmpty() && studentEmail != null && !studentEmail.isBlank()) {
            normalizedEmails.add(studentEmail.trim().toLowerCase());
        }

        if (normalizedEmails.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select at least one student.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        int enrolledCount = 0;
        int alreadyEnrolledCount = 0;
        int invalidCount = 0;

        for (String normalizedEmail : normalizedEmails) {
            Optional<User> studentOpt = userRepository.findByEmail(normalizedEmail);
            if (studentOpt.isEmpty() || studentOpt.get().getRole() != User.Role.STUDENT) {
                invalidCount += 1;
                continue;
            }

            if (enrolledStudentRepository.findByTeacherEmailAndStudentEmailAndSubjectId(subject.getTeacherEmail(), normalizedEmail, subjectId).isPresent()) {
                alreadyEnrolledCount += 1;
                continue;
            }

            User student = studentOpt.get();
            String studentName = (student.getFullName() == null || student.getFullName().isBlank())
                ? normalizedEmail
                : student.getFullName().trim();
            EnrolledStudent enrollment = new EnrolledStudent(
                subject.getTeacherEmail(),
                normalizedEmail,
                studentName,
                subject.getId(),
                subject.getSubjectName()
            );
            enrolledStudentRepository.save(enrollment);
            enrolledCount += 1;
        }

        if (enrolledCount == 0) {
            if (alreadyEnrolledCount > 0 && invalidCount == 0) {
                redirectAttributes.addFlashAttribute("errorMessage", "Selected students are already enrolled in this subject.");
            } else if (alreadyEnrolledCount == 0 && invalidCount > 0) {
                redirectAttributes.addFlashAttribute("errorMessage", "No valid student accounts found in the selected list.");
            } else {
                redirectAttributes.addFlashAttribute("errorMessage",
                    "No students were enrolled. " + alreadyEnrolledCount + " already enrolled, " + invalidCount + " invalid.");
            }
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        redirectAttributes.addFlashAttribute("successMessage", enrolledCount + " student(s) enrolled successfully.");
        if (alreadyEnrolledCount > 0 || invalidCount > 0) {
            redirectAttributes.addFlashAttribute("warningMessage",
                "Skipped " + alreadyEnrolledCount + " already enrolled and " + invalidCount + " invalid account(s).");
        }
        return "redirect:/teacher/subject-classroom/" + subjectId;
    }

    @PostMapping("/enroll-students/import")
    public String importStudents(@RequestParam("subjectId") Long subjectId,
                                 @RequestParam("departmentName") String departmentName,
                                 @RequestParam(name = "programName", required = false) String programName,
                                 @RequestParam("studentListFile") MultipartFile studentListFile,
                                 Principal principal,
                                 RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(subjectId);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this subject.");
            return "redirect:/teacher/subjects";
        }

        String normalizedDepartment = departmentName == null ? "" : departmentName.trim();
        String normalizedProgram = programName == null ? "" : programName.trim();
        if (normalizedDepartment.isBlank() || !DEPARTMENTS.contains(normalizedDepartment)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid department before importing.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        if (!AcademicCatalog.isValidProgram(normalizedDepartment, normalizedProgram)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select a valid program for the selected department.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        if (studentListFile == null || studentListFile.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please upload a CSV file.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
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
                String effectivePassword = rawPassword.isBlank() ? generateStudentPassword() : rawPassword;

                User student = userRepository.findByEmail(rawEmail).orElse(null);
                if (student == null) {
                    student = new User();
                    student.setEmail(rawEmail);
                    student.setPassword(passwordEncoder.encode(effectivePassword));
                    student.setFullName(effectiveName);
                    student.setSchoolName(SCHOOL_NAME);
                    student.setCampusName(CAMPUS_NAME);
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
                    if (!SCHOOL_NAME.equals(student.getSchoolName())) {
                        student.setSchoolName(SCHOOL_NAME);
                        changed = true;
                    }
                    if (!CAMPUS_NAME.equals(student.getCampusName())) {
                        student.setCampusName(CAMPUS_NAME);
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

                if (enrolledStudentRepository.findByTeacherEmailAndStudentEmailAndSubjectId(subject.getTeacherEmail(), rawEmail, subjectId).isEmpty()) {
                    EnrolledStudent enrollment = new EnrolledStudent(
                        subject.getTeacherEmail(),
                        rawEmail,
                        effectiveName,
                        subject.getId(),
                        subject.getSubjectName()
                    );
                    enrolledStudentRepository.save(enrollment);
                    enrolledCount++;
                }
            }
        } catch (IOException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to read the uploaded CSV file.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        if (rowsRead == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No student rows found. Use CSV format: Full Name,Email,Password(optional).");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        String summary = "Import complete: "
            + enrolledCount + " enrolled, "
            + createdAccounts + " account(s) created, "
            + updatedAccounts + " account(s) updated, "
            + skippedRows + " row(s) skipped.";
        redirectAttributes.addFlashAttribute("successMessage", summary);
        return "redirect:/teacher/subject-classroom/" + subjectId;
    }

    @GetMapping("/subject-classroom/{id}/distributed-exams")
    public String subjectDistributedExams(@PathVariable("id") Long id,
                                          Model model,
                                          Principal principal,
                                          RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        String teacherEmail = principal != null ? principal.getName() : "";
        if (!teacherEmail.isBlank() && !teacherEmail.equalsIgnoreCase(subject.getTeacherEmail())) {
            return "redirect:/teacher/subjects";
        }

        List<DistributedExam> distributedExams = distributedExamRepository.findAll().stream()
            .filter(item -> item.getSubject() != null && item.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .toList();

        try {
            List<Map<String, Object>> quizDistributionSummary = buildQuizDistributionSummary(distributedExams);

            model.addAttribute("subject", subject);
            model.addAttribute("quizDistributionSummary", quizDistributionSummary);
            model.addAttribute("distributedExamCount", quizDistributionSummary.size());
            return "subject-distributed-exams";
        } catch (Exception exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to load distributed exams right now.");
            return "redirect:/teacher/subject-classroom/" + id;
        }
    }

    @PostMapping("/subject-classroom/{id}/distributed-exams/delete")
    public String deleteDistributedExamBatch(@PathVariable("id") Long id,
                                             @RequestParam(name = "distributionId", required = false) Long distributionId,
                                             @RequestParam(name = "examId", required = false) String examId,
                                             @RequestParam("examName") String examName,
                                             @RequestParam("activityType") String activityType,
                                             @RequestParam("timeLimit") Integer timeLimit,
                                             @RequestParam(name = "deadline", required = false) String deadline,
                                             Principal principal,
                                             RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to modify this subject.");
            return "redirect:/teacher/subjects";
        }

        if (distributionId != null) {
            Optional<DistributedExam> distributionOpt = distributedExamRepository.findById(distributionId);
            if (distributionOpt.isEmpty()
                || !sameText(distributionOpt.get().getSubject(), subject.getSubjectName())) {
                redirectAttributes.addFlashAttribute("errorMessage", "Distributed quiz record not found.");
                return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
            }

            DistributedExam distribution = distributionOpt.get();
            List<ExamSubmission> linkedSubmissions = examSubmissionRepository
                .findByStudentEmailAndExamNameAndSubject(
                    distribution.getStudentEmail(),
                    distribution.getExamName(),
                    distribution.getSubject())
                .stream()
                .filter(submission -> distributionId.equals(extractSubmissionDistributedExamId(submission)))
                .toList();

            distributedExamRepository.delete(distribution);
            if (!linkedSubmissions.isEmpty()) {
                examSubmissionRepository.deleteAll(linkedSubmissions);
            }

            redirectAttributes.addFlashAttribute("successMessage",
                "Distributed quiz deleted successfully (1 assignment, "
                    + linkedSubmissions.size() + " submission(s)).");
            return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
        }

        String normalizedDeadline = deadline == null ? "" : deadline.trim();

        List<DistributedExam> matchingDistributions = distributedExamRepository.findAll().stream()
            .filter(item -> item.getSubject() != null && item.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .filter(item -> examId == null || examId.isBlank() || sameText(item.getExamId(), examId))
            .filter(item -> (examId != null && !examId.isBlank()) || (examName != null && examName.equals(item.getExamName())))
            .filter(item -> activityType != null && activityType.equals(item.getActivityType()))
            .filter(item -> timeLimit != null && timeLimit.equals(item.getTimeLimit()))
            .filter(item -> {
                String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                return itemDeadline.equals(normalizedDeadline);
            })
            .toList();

        List<ExamSubmission> matchingSubmissions = examSubmissionRepository.findAll().stream()
            .filter(sub -> sub.getSubject() != null && sub.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .filter(sub -> examId == null || examId.isBlank() || sameText(extractSubmissionExamId(sub), examId))
            .filter(sub -> (examId != null && !examId.isBlank()) || (examName != null && examName.equals(sub.getExamName())))
            .filter(sub -> activityType != null && activityType.equals(sub.getActivityType()))
            .filter(sub -> timeLimit != null && timeLimit.equals(sub.getTimeLimit()))
            .filter(sub -> {
                String payloadDeadline = "";
                Map<String, Object> payload = parseFlexibleMapJson(sub.getAnswerDetailsJson());
                if (payload.get("deadline") != null) {
                    payloadDeadline = String.valueOf(payload.get("deadline")).trim();
                }
                return payloadDeadline.equals(normalizedDeadline);
            })
            .toList();

        if (matchingDistributions.isEmpty() && matchingSubmissions.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No matching distributed quiz batch found.");
            return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
        }

        if (!matchingDistributions.isEmpty()) {
            distributedExamRepository.deleteAll(matchingDistributions);
        }
        if (!matchingSubmissions.isEmpty()) {
            examSubmissionRepository.deleteAll(matchingSubmissions);
        }

        redirectAttributes.addFlashAttribute("successMessage",
            "Distributed quiz batch deleted successfully (" + matchingDistributions.size() + " assignment(s), "
                + matchingSubmissions.size() + " submission(s)).");
        return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
    }

    @PostMapping("/distribute-selected")
    public String distributeSelected(@RequestParam("subjectId") Long subjectId,
                                     @RequestParam("examId") String examId,
                                     @RequestParam("questionCount") Integer questionCount,
                                     @RequestParam("timeLimit") Integer timeLimit,
                                     @RequestParam("easyPercent") Integer easyPercent,
                                     @RequestParam("mediumPercent") Integer mediumPercent,
                                     @RequestParam("hardPercent") Integer hardPercent,
                                     @RequestParam(name = "deadline", required = false) String deadline,
                                     @RequestParam(required = false, name = "selectedStudents") List<String> selectedStudents,
                                     Principal principal,
                                     RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(subjectId);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        String teacherEmail = principal != null ? principal.getName() : "";
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to distribute quizzes for this subject.");
            return "redirect:/teacher/subjects";
        }

        Set<String> uniqueSelectedStudents = selectedStudents == null
            ? new LinkedHashSet<>()
            : selectedStudents.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (uniqueSelectedStudents.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please select at least one student.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        if (questionCount == null || questionCount <= 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "Question count must be at least 1.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        if ((easyPercent == null ? 0 : easyPercent)
            + (mediumPercent == null ? 0 : mediumPercent)
            + (hardPercent == null ? 0 : hardPercent) != 100) {
            redirectAttributes.addFlashAttribute("errorMessage", "Difficulty distribution must total 100%.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);
        if (paperOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Processed exam not found.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        OriginalProcessedPaper paper = paperOpt.get();
        if (!isOwner(principal, paper.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to distribute this exam.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        if (paper.getSubject() == null || !paper.getSubject().equalsIgnoreCase(subject.getSubjectName())) {
            redirectAttributes.addFlashAttribute("errorMessage", "Selected exam does not belong to this subject.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> difficultiesMap = parseSimpleMapJson(paper.getDifficultiesJson());
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());

        if (questions.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No questions available in selected exam.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        int requestedCount = Math.min(questionCount, questions.size());
        List<Integer> selectedIndexes = selectQuestionIndexesByDifficulty(
            requestedCount,
            easyPercent == null ? 0 : easyPercent,
            mediumPercent == null ? 0 : mediumPercent,
            hardPercent == null ? 0 : hardPercent,
            difficultiesMap,
            questions.size()
        );

        if (selectedIndexes.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No questions available for distribution.");
            return "redirect:/teacher/subject-classroom/" + subjectId;
        }

        List<Map<String, Object>> selectedQuestions = buildQuestionSubset(questions, selectedIndexes);
        Map<String, String> selectedDifficulties = buildSelectedMapSubset(selectedIndexes, difficultiesMap, "Medium");
        Map<String, String> selectedAnswerKey = buildSelectedMapSubset(selectedIndexes, answerKeyMap, "");
        String distributedQuestionsJson = gson.toJson(selectedQuestions);
        String distributedDifficultiesJson = gson.toJson(selectedDifficulties);
        String distributedAnswerKeyJson = gson.toJson(selectedAnswerKey);

        // Exam distribution: only mark exam as available for students.
        int attempted = uniqueSelectedStudents.size();
        int distributed = 0;
        int failed = 0;
        Set<String> usedQuestionOrders = new HashSet<>();
        for (String studentEmail : uniqueSelectedStudents) {
            String normalizedStudentEmail = studentEmail == null ? "" : studentEmail.trim().toLowerCase();
            if (normalizedStudentEmail.isBlank()) {
                failed++;
                continue;
            }

            List<DistributedExam> existingActive = distributedExamRepository.findAll().stream()
                .filter(item -> item != null)
                .filter(item -> !item.isSubmitted())
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> sameText(item.getExamId(), paper.getExamId()))
                .filter(item -> sameText(item.getStudentEmail(), normalizedStudentEmail))
                .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

            DistributedExam distExam = existingActive.isEmpty() ? new DistributedExam() : existingActive.get(0);
            if (existingActive.size() > 1) {
                distributedExamRepository.deleteAll(existingActive.subList(1, existingActive.size()));
            }

            distExam.setStudentEmail(normalizedStudentEmail);
            distExam.setExamId(paper.getExamId());
            distExam.setSubject(subject.getSubjectName());
            distExam.setExamName(paper.getExamName());
            distExam.setActivityType(paper.getActivityType());
            distExam.setTimeLimit((timeLimit != null && timeLimit > 0) ? timeLimit : 60);
            distExam.setDeadline(deadline == null ? "" : deadline);
            distExam.setDistributedAt(java.time.LocalDateTime.now());
            distExam.setSubmitted(false);
            List<Integer> studentQuestionIndexes = buildUniqueQuestionOrder(selectedIndexes, usedQuestionOrders);
            distExam.setQuestionIndexesJson(gson.toJson(studentQuestionIndexes));
            distributedExamRepository.save(distExam);
            distributed++;
        }

        if (distributed == 0) {
            redirectAttributes.addFlashAttribute("errorMessage",
                attempted == 0
                    ? "Quiz distribution failed. No valid students were selected."
                    : "Quiz distribution failed. Could not distribute to selected students.");
        } else if (failed > 0) {
            redirectAttributes.addFlashAttribute("warningMessage",
                "Distributed to " + distributed + " student(s), but failed for " + failed + " student(s).");
        }
        return "redirect:/teacher/subject-classroom/" + subjectId;
    }

    @GetMapping("/subject-classroom/{id}/enrolled-students")
    public String subjectEnrolledStudents(@PathVariable("id") Long id, Model model, Principal principal) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        String teacherEmail = principal != null ? principal.getName() : "";
        if (!teacherEmail.isBlank() && !teacherEmail.equalsIgnoreCase(subject.getTeacherEmail())) {
            return "redirect:/teacher/subjects";
        }

        List<EnrolledStudent> enrolledStudents = enrolledStudentRepository.findBySubjectId(id);
        Map<String, String> studentProgramByEmail = new HashMap<>();
        for (EnrolledStudent student : enrolledStudents) {
            String rawEmail = student == null ? "" : (student.getStudentEmail() == null ? "" : student.getStudentEmail().trim());
            if (rawEmail.isBlank()) {
                continue;
            }
            String key = rawEmail.toLowerCase();
            String programName = userRepository.findByEmail(rawEmail)
                .map(User::getProgramName)
                .orElse("");
            studentProgramByEmail.put(key, programName == null ? "" : programName.trim());
        }
        model.addAttribute("subject", subject);
        model.addAttribute("enrolledStudents", enrolledStudents);
        model.addAttribute("studentProgramByEmail", studentProgramByEmail);
        model.addAttribute("enrolledCount", enrolledStudents.size());
        return "subject-enrolled-students";
    }

    @GetMapping("/subject-classroom/{id}/distribution-students")
    public String subjectDistributionStudents(@PathVariable("id") Long id,
                                             @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                             @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                             @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                             @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                             @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                             @RequestParam(name = "distributionId", required = false) Long distributionId,
                                             Model model,
                                             Principal principal) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        String teacherEmail = principal != null ? principal.getName() : "";
        if (!teacherEmail.isBlank() && !teacherEmail.equalsIgnoreCase(subject.getTeacherEmail())) {
            return "redirect:/teacher/subjects";
        }
        String normalizedFilterExamId = (filterExamId == null || filterExamId.trim().isBlank()) ? null : filterExamId.trim();

        List<DistributedExam> matchingDistributions = distributedExamRepository.findAll().stream()
            .filter(item -> item != null)
            .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
            .filter(item -> distributionId == null || distributionId.equals(item.getId()))
            .filter(item -> distributionId != null || normalizedFilterExamId == null || sameText(item.getExamId(), normalizedFilterExamId))
            .filter(item -> distributionId != null || normalizedFilterExamId != null || filterExamName == null || sameText(item.getExamName(), filterExamName))
            .filter(item -> distributionId != null || filterActivityType == null || sameText(item.getActivityType(), filterActivityType))
            .filter(item -> distributionId != null || filterTimeLimit == null || filterTimeLimit.equals(item.getTimeLimit()))
            .filter(item -> {
                if (distributionId != null || filterDeadline == null) {
                    return true;
                }
                String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                return itemDeadline.equals(filterDeadline.trim());
            })
            .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        if (distributionId != null && matchingDistributions.isEmpty()) {
            return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
        }

        Map<String, DistributedExam> latestByStudent = new LinkedHashMap<>();
        for (DistributedExam item : matchingDistributions) {
            String emailKey = item.getStudentEmail() == null ? "" : item.getStudentEmail().trim().toLowerCase();
            if (emailKey.isBlank() || latestByStudent.containsKey(emailKey)) {
                continue;
            }
            latestByStudent.put(emailKey, item);
        }
        List<DistributedExam> scopedDistributions = distributionId != null
            ? matchingDistributions.stream().limit(1).toList()
            : new ArrayList<>(latestByStudent.values());

        DistributedExam filterSource = scopedDistributions.isEmpty() ? null : scopedDistributions.get(0);
        String effectiveExamId = filterSource != null ? filterSource.getExamId() : normalizedFilterExamId;
        String effectiveExamName = filterSource != null ? filterSource.getExamName() : filterExamName;
        String effectiveActivityType = filterSource != null ? filterSource.getActivityType() : filterActivityType;
        Integer effectiveTimeLimit = filterSource != null ? filterSource.getTimeLimit() : filterTimeLimit;
        String effectiveDeadline = filterSource != null
            ? (filterSource.getDeadline() == null ? "" : filterSource.getDeadline().trim())
            : (filterDeadline == null ? "" : filterDeadline.trim());

        LocalDateTime filterDeadlineAt = parseDeadlineValue(effectiveDeadline);
        boolean deadlinePassed = filterDeadlineAt != null && LocalDateTime.now().isAfter(filterDeadlineAt);

        List<EnrolledStudent> enrolledStudents = enrolledStudentRepository.findBySubjectId(subject.getId());
        Map<String, String> studentNameByEmail = new HashMap<>();
        for (EnrolledStudent student : enrolledStudents) {
            if (student == null || student.getStudentEmail() == null) {
                continue;
            }
            String key = student.getStudentEmail().trim().toLowerCase();
            if (key.isBlank()) {
                continue;
            }
            studentNameByEmail.put(key, student.getStudentName());
        }

        Map<String, Map<String, String>> generatedOtpByEmail = extractGeneratedOtpByEmail(model);

        List<Map<String, Object>> submittedStudents = new ArrayList<>();
        List<Map<String, Object>> notSubmittedStudents = new ArrayList<>();
        List<Map<String, Object>> queuedStudents = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        String distributionReturnTo = buildDistributionStudentsReturnTo(
            id,
            effectiveExamId,
            effectiveExamName,
            effectiveActivityType,
            effectiveTimeLimit,
            effectiveDeadline,
            distributionId);

        for (DistributedExam distribution : scopedDistributions) {
            ExamSubmission submission = findLatestSubmissionForDistributedExam(distribution);

            String rawEmail = distribution.getStudentEmail() == null ? "" : distribution.getStudentEmail().trim();
            String studentName = studentNameByEmail.getOrDefault(rawEmail.toLowerCase(), rawEmail);

            Map<String, Object> item = new HashMap<>();
            item.put("distributionId", distribution.getId());
            item.put("studentName", studentName == null || studentName.isBlank() ? rawEmail : studentName);
            item.put("studentEmail", rawEmail);
            item.put("examName", distribution.getExamName() == null ? "" : distribution.getExamName());
            item.put("activityType", distribution.getActivityType() == null ? "Quiz" : distribution.getActivityType());
            item.put("deadline", formatDeadline(distribution.getDeadline()));
            item.put("lastSubmittedAt", submission != null && submission.getSubmittedAt() != null ? submission.getSubmittedAt().toString() : "-");
            item.put("submissionId", submission != null ? submission.getId() : null);
            item.put("viewResultUrl", submission != null
                ? "/teacher/view-result/" + submission.getId() + buildReturnToQuery(distributionReturnTo)
                : null);
            item.put("viewGradeUrl", submission != null
                ? "/teacher/grade/" + submission.getId() + buildReturnToQuery(distributionReturnTo)
                : null);

            int openEndedCount = 0;
            boolean needsManualGrading = false;
            boolean graded = false;
            boolean released = false;

            if (submission != null) {
                List<Map<String, Object>> answerDetails = extractAnswerDetails(submission.getAnswerDetailsJson());
                openEndedCount = (int) answerDetails.stream().filter(this::isOpenEndedAnswerDetail).count();
                graded = submission.isGraded();
                released = submission.isResultsReleased();
                needsManualGrading = openEndedCount > 0 && !graded;
            }

            item.put("openEndedCount", openEndedCount);
            item.put("needsManualGrading", needsManualGrading);
            item.put("isGraded", graded);
            item.put("resultsReleased", released);
            item.put("otpStatusLabel", buildOtpStatusLabel(distribution, now));
            item.put("otpStatusClass", buildOtpStatusClass(distribution, now));
            item.put("otpActionLabel", resolveOtpActionLabel(distribution, now));
            item.put("otpAvailableForGeneration", !distribution.isSubmitted());

            Map<String, String> generatedOtp = generatedOtpByEmail.get(rawEmail.toLowerCase());
            item.put("generatedOtpCode", generatedOtp == null ? "" : generatedOtp.getOrDefault("otpCode", ""));
            item.put("generatedOtpExpiresAt", generatedOtp == null ? "" : generatedOtp.getOrDefault("expiresAt", ""));

            boolean completedSubmission = submission != null && isSubmissionCompleted(submission);
            if (completedSubmission || (submission == null && distribution.isSubmitted())) {
                submittedStudents.add(item);
            } else if (submission != null) {
                notSubmittedStudents.add(item);
            } else {
                queuedStudents.add(item);
            }
        }

        int submittedCount = submittedStudents.size();
        int notSubmittedCount = notSubmittedStudents.size();
        int queuedCount = queuedStudents.size();
        int missedCount = deadlinePassed ? notSubmittedCount + queuedCount : 0;
        int pendingCount = deadlinePassed ? 0 : notSubmittedCount + queuedCount;
        int totalTrackedCount = submittedCount + notSubmittedCount + queuedCount;

        model.addAttribute("subject", subject);
        model.addAttribute("submittedStudents", submittedStudents);
        model.addAttribute("notSubmittedStudents", notSubmittedStudents);
        model.addAttribute("queuedStudents", queuedStudents);
        model.addAttribute("submittedCount", submittedCount);
        model.addAttribute("notSubmittedCount", notSubmittedCount);
        model.addAttribute("queuedCount", queuedCount);
        model.addAttribute("missedCount", missedCount);
        model.addAttribute("pendingCount", pendingCount);
        model.addAttribute("deadlinePassed", deadlinePassed);
        model.addAttribute("totalTrackedCount", totalTrackedCount);

        model.addAttribute("filterExamId", effectiveExamId);
        model.addAttribute("filterExamName", effectiveExamName);
        model.addAttribute("filterActivityType", effectiveActivityType);
        model.addAttribute("filterTimeLimit", effectiveTimeLimit);
        model.addAttribute("filterDeadline", effectiveDeadline);
        model.addAttribute("distributionId", distributionId);
        model.addAttribute("activeQuizFilter",
            distributionId != null
                || (effectiveExamId != null && !effectiveExamId.isBlank())
                || effectiveExamName != null
                || effectiveActivityType != null
                || effectiveTimeLimit != null
                || (effectiveDeadline != null && !effectiveDeadline.isBlank()));

        return "subject-distribution-students";
    }

    private Map<String, Map<String, String>> extractGeneratedOtpByEmail(Model model) {
        Map<String, Map<String, String>> generated = new HashMap<>();
        if (model == null) {
            return generated;
        }

        Object raw = model.asMap().get("generatedOtpRows");
        if (!(raw instanceof List<?> rows)) {
            return generated;
        }

        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }

            Object rawEmail = row.get("studentEmail");
            Object rawOtpCode = row.get("otpCode");
            Object rawExpiresAt = row.get("expiresAt");

            String email = rawEmail == null ? "" : String.valueOf(rawEmail).trim().toLowerCase();
            String otpCode = rawOtpCode == null ? "" : String.valueOf(rawOtpCode).trim();
            String expiresAt = rawExpiresAt == null ? "" : String.valueOf(rawExpiresAt).trim();
            if (email.isBlank() || otpCode.isBlank()) {
                continue;
            }

            Map<String, String> otpData = new HashMap<>();
            otpData.put("otpCode", otpCode);
            otpData.put("expiresAt", expiresAt);
            generated.put(email, otpData);
        }

        return generated;
    }

    @PostMapping("/subject-classroom/{id}/distribution-students/generate-otp")
    public String generateDistributionOtp(@PathVariable("id") Long id,
                                          @RequestParam("studentEmail") String studentEmail,
                                          @RequestParam(name = "distributionId", required = false) Long distributionId,
                                          @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                          @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                          @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                          @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                          @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                          Principal principal,
                                          RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to generate OTP for this quiz.");
            return "redirect:/teacher/subjects";
        }
        String normalizedFilterExamId = (filterExamId == null || filterExamId.trim().isBlank()) ? null : filterExamId.trim();

        String normalizedStudentEmail = studentEmail == null ? "" : studentEmail.trim();
        if (normalizedStudentEmail.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Student email is required.");
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                normalizedFilterExamId,
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                distributionId);
        }

        DistributedExam target = null;
        if (distributionId != null) {
            target = distributedExamRepository.findById(distributionId)
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> sameText(item.getStudentEmail(), normalizedStudentEmail))
                .orElse(null);
        }

        if (target == null) {
            String normalizedDeadline = filterDeadline == null ? "" : filterDeadline.trim();
            target = distributedExamRepository.findAll().stream()
                .filter(item -> item != null)
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> sameText(item.getStudentEmail(), normalizedStudentEmail))
                .filter(item -> normalizedFilterExamId == null || sameText(item.getExamId(), normalizedFilterExamId))
                .filter(item -> normalizedFilterExamId != null || filterExamName == null || sameText(item.getExamName(), filterExamName))
                .filter(item -> filterActivityType == null || sameText(item.getActivityType(), filterActivityType))
                .filter(item -> filterTimeLimit == null || filterTimeLimit.equals(item.getTimeLimit()))
                .filter(item -> {
                    String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                    return normalizedDeadline.isBlank() || itemDeadline.equals(normalizedDeadline);
                })
                .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
        }

        if (target == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "No distributed quiz record found for this student.");
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                normalizedFilterExamId,
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                distributionId);
        }

        if (target.isSubmitted()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Cannot generate OTP because this student has already submitted.");
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                target.getExamId(),
                target.getExamName(),
                target.getActivityType(),
                target.getTimeLimit(),
                target.getDeadline(),
                target.getId());
        }

        String otpCode = generateNumericOtpCode(ACCESS_OTP_LENGTH);
        LocalDateTime generatedAt = LocalDateTime.now();
        LocalDateTime expiresAt = generatedAt.plusMinutes(ACCESS_OTP_VALID_MINUTES);

        List<DistributedExam> otpTargets = findActiveBatchDistributionsForStudent(target);
        if (otpTargets.isEmpty()) {
            otpTargets = List.of(target);
        }

        for (DistributedExam otpTarget : otpTargets) {
            otpTarget.setAccessOtpHash(passwordEncoder.encode(otpCode));
            otpTarget.setAccessOtpGeneratedAt(generatedAt);
            otpTarget.setAccessOtpExpiresAt(expiresAt);
            otpTarget.setAccessOtpVerifiedAt(null);
        }
        distributedExamRepository.saveAll(otpTargets);

        String studentName = enrolledStudentRepository.findBySubjectId(subject.getId()).stream()
            .filter(enrolled -> enrolled != null)
            .filter(enrolled -> sameText(enrolled.getStudentEmail(), normalizedStudentEmail))
            .map(EnrolledStudent::getStudentName)
            .filter(name -> name != null && !name.isBlank())
            .findFirst()
            .orElse(normalizedStudentEmail);

        Map<String, Object> generatedRow = new HashMap<>();
        generatedRow.put("studentName", studentName);
        generatedRow.put("studentEmail", normalizedStudentEmail);
        generatedRow.put("otpCode", otpCode);
        generatedRow.put("expiresAt", expiresAt.format(DEADLINE_DISPLAY_FORMAT));
        redirectAttributes.addFlashAttribute("generatedOtpRows", List.of(generatedRow));

        redirectAttributes.addFlashAttribute(
            "successMessage",
            "Generated/regenerated OTP for " + normalizedStudentEmail
                + ". Copy the code below now.");

        return "redirect:" + buildDistributionStudentsReturnTo(
            id,
            target.getExamId(),
            target.getExamName(),
            target.getActivityType(),
            target.getTimeLimit(),
            target.getDeadline(),
            target.getId());
    }

    @GetMapping("/subject-classroom/{id}/distribution-students/generate-otp")
    public String generateDistributionOtpGet(@PathVariable("id") Long id,
                                             @RequestParam(name = "studentEmail", required = false) String studentEmail,
                                             @RequestParam(name = "distributionId", required = false) Long distributionId,
                                             @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                             @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                             @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                             @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                             @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                             RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", "Use the Generate Code button to submit OTP generation.");
        return "redirect:" + buildDistributionStudentsReturnTo(
            id,
            filterExamId,
            filterExamName,
            filterActivityType,
            filterTimeLimit,
            filterDeadline,
            distributionId);
    }

    @PostMapping("/subject-classroom/{id}/distribution-students/generate-otp-all")
    public String generateDistributionOtpForAll(@PathVariable("id") Long id,
                                                @RequestParam(name = "distributionId", required = false) Long distributionId,
                                                @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                                @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                                @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                                @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                                @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                                Principal principal,
                                                RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to generate OTPs for this quiz.");
            return "redirect:/teacher/subjects";
        }

        String normalizedFilterExamId = (filterExamId == null || filterExamId.trim().isBlank()) ? null : filterExamId.trim();
        String normalizedDeadline = filterDeadline == null ? "" : filterDeadline.trim();

        List<DistributedExam> matchingDistributions = distributedExamRepository.findAll().stream()
            .filter(item -> item != null)
            .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
            .filter(item -> distributionId == null || distributionId.equals(item.getId()))
            .filter(item -> distributionId != null || normalizedFilterExamId == null || sameText(item.getExamId(), normalizedFilterExamId))
            .filter(item -> distributionId != null || normalizedFilterExamId != null || filterExamName == null || sameText(item.getExamName(), filterExamName))
            .filter(item -> distributionId != null || filterActivityType == null || sameText(item.getActivityType(), filterActivityType))
            .filter(item -> distributionId != null || filterTimeLimit == null || filterTimeLimit.equals(item.getTimeLimit()))
            .filter(item -> {
                if (distributionId != null || filterDeadline == null) {
                    return true;
                }
                String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                return itemDeadline.equals(normalizedDeadline);
            })
            .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        Map<String, DistributedExam> latestByStudent = new LinkedHashMap<>();
        for (DistributedExam item : matchingDistributions) {
            String emailKey = item.getStudentEmail() == null ? "" : item.getStudentEmail().trim().toLowerCase();
            if (emailKey.isBlank() || latestByStudent.containsKey(emailKey)) {
                continue;
            }
            latestByStudent.put(emailKey, item);
        }

        List<DistributedExam> scopedDistributions = distributionId != null
            ? matchingDistributions.stream().limit(1).toList()
            : new ArrayList<>(latestByStudent.values());

        DistributedExam filterSource = scopedDistributions.isEmpty() ? null : scopedDistributions.get(0);
        String effectiveExamId = filterSource != null ? filterSource.getExamId() : normalizedFilterExamId;
        String effectiveExamName = filterSource != null ? filterSource.getExamName() : filterExamName;
        String effectiveActivityType = filterSource != null ? filterSource.getActivityType() : filterActivityType;
        Integer effectiveTimeLimit = filterSource != null ? filterSource.getTimeLimit() : filterTimeLimit;
        String effectiveDeadline = filterSource != null
            ? (filterSource.getDeadline() == null ? "" : filterSource.getDeadline().trim())
            : normalizedDeadline;
        Long effectiveDistributionId = filterSource != null ? filterSource.getId() : distributionId;

        if (scopedDistributions.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No distributed quiz record found for OTP generation.");
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                effectiveExamId,
                effectiveExamName,
                effectiveActivityType,
                effectiveTimeLimit,
                effectiveDeadline,
                effectiveDistributionId);
        }

        List<EnrolledStudent> enrolledStudents = enrolledStudentRepository.findBySubjectId(subject.getId());
        Map<String, String> studentNameByEmail = new HashMap<>();
        for (EnrolledStudent student : enrolledStudents) {
            if (student == null || student.getStudentEmail() == null) {
                continue;
            }
            String key = student.getStudentEmail().trim().toLowerCase();
            if (!key.isBlank()) {
                studentNameByEmail.put(key, student.getStudentName());
            }
        }

        List<DistributedExam> toSave = new ArrayList<>();
        List<Map<String, Object>> generatedOtpRows = new ArrayList<>();
        int skippedSubmitted = 0;

        for (DistributedExam target : scopedDistributions) {
            if (target == null) {
                continue;
            }
            if (target.isSubmitted()) {
                skippedSubmitted++;
                continue;
            }

            String otpCode = generateNumericOtpCode(ACCESS_OTP_LENGTH);
            LocalDateTime generatedAt = LocalDateTime.now();
            LocalDateTime expiresAt = generatedAt.plusMinutes(ACCESS_OTP_VALID_MINUTES);

            List<DistributedExam> otpTargets = findActiveBatchDistributionsForStudent(target);
            if (otpTargets.isEmpty()) {
                otpTargets = List.of(target);
            }

            for (DistributedExam otpTarget : otpTargets) {
                otpTarget.setAccessOtpHash(passwordEncoder.encode(otpCode));
                otpTarget.setAccessOtpGeneratedAt(generatedAt);
                otpTarget.setAccessOtpExpiresAt(expiresAt);
                otpTarget.setAccessOtpVerifiedAt(null);
                toSave.add(otpTarget);
            }

            String studentEmail = target.getStudentEmail() == null ? "" : target.getStudentEmail().trim();
            String studentName = studentNameByEmail.getOrDefault(studentEmail.toLowerCase(), studentEmail);

            Map<String, Object> row = new HashMap<>();
            row.put("studentName", studentName == null || studentName.isBlank() ? studentEmail : studentName);
            row.put("studentEmail", studentEmail);
            row.put("otpCode", otpCode);
            row.put("expiresAt", expiresAt.format(DEADLINE_DISPLAY_FORMAT));
            generatedOtpRows.add(row);
        }

        if (toSave.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                skippedSubmitted > 0
                    ? "No OTP generated. All tracked students have already submitted."
                    : "No OTP generated. No eligible student records found."
            );
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                effectiveExamId,
                effectiveExamName,
                effectiveActivityType,
                effectiveTimeLimit,
                effectiveDeadline,
                effectiveDistributionId);
        }

        distributedExamRepository.saveAll(toSave);

        redirectAttributes.addFlashAttribute("generatedOtpRows", generatedOtpRows);
        redirectAttributes.addFlashAttribute(
            "successMessage",
            "Generated/regenerated OTP for " + toSave.size() + " student(s). Copy the codes below now."
        );
        if (skippedSubmitted > 0) {
            redirectAttributes.addFlashAttribute(
                "warningMessage",
                "Skipped " + skippedSubmitted + " submitted student(s)."
            );
        }

        return "redirect:" + buildDistributionStudentsReturnTo(
            id,
            effectiveExamId,
            effectiveExamName,
            effectiveActivityType,
            effectiveTimeLimit,
            effectiveDeadline,
            effectiveDistributionId);
    }

    private List<DistributedExam> findActiveBatchDistributionsForStudent(DistributedExam source) {
        if (source == null || source.isSubmitted()) {
            return new ArrayList<>();
        }

        String studentEmail = source.getStudentEmail() == null ? "" : source.getStudentEmail().trim();
        String batchKey = buildDistributionBatchKey(source);
        if (studentEmail.isBlank() || batchKey.isBlank()) {
            return new ArrayList<>();
        }

        return distributedExamRepository.findAll().stream()
            .filter(item -> item != null)
            .filter(item -> !item.isSubmitted())
            .filter(item -> sameText(item.getStudentEmail(), studentEmail))
            .filter(item -> batchKey.equals(buildDistributionBatchKey(item)))
            .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @GetMapping("/subject-classroom/{id}/distribution-students/generate-otp-all")
    public String generateDistributionOtpForAllGet(@PathVariable("id") Long id,
                                                   @RequestParam(name = "distributionId", required = false) Long distributionId,
                                                   @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                                   @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                                   @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                                   @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                                   @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                                   RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", "Use the Generate / Regenerate OTP button to submit OTP generation.");
        return "redirect:" + buildDistributionStudentsReturnTo(
            id,
            filterExamId,
            filterExamName,
            filterActivityType,
            filterTimeLimit,
            filterDeadline,
            distributionId);
    }

    @PostMapping("/subject-classroom/{id}/distribution-students/reopen")
    public String reopenDistributedQuizForStudent(@PathVariable("id") Long id,
                                                  @RequestParam("studentEmail") String studentEmail,
                                                  @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                                  @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                                  @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                                  @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                                  @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                                  @RequestParam(name = "distributionId", required = false) Long distributionId,
                                                  @RequestParam(name = "reopenPreset", required = false, defaultValue = "24h") String reopenPreset,
                                                  @RequestParam(name = "customDeadline", required = false) String customDeadline,
                                                  Principal principal,
                                                  RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to reopen this quiz.");
            return "redirect:/teacher/subjects";
        }
        String normalizedFilterExamId = (filterExamId == null || filterExamId.trim().isBlank()) ? null : filterExamId.trim();

        String normalizedStudentEmail = studentEmail == null ? "" : studentEmail.trim();
        if (normalizedStudentEmail.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Student email is required.");
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                normalizedFilterExamId,
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                distributionId);
        }

        DistributedExam source = null;
        if (distributionId != null) {
            source = distributedExamRepository.findById(distributionId)
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> sameText(item.getStudentEmail(), normalizedStudentEmail))
                .orElse(null);
        }

        if (source == null) {
            String normalizedDeadline = filterDeadline == null ? "" : filterDeadline.trim();
            source = distributedExamRepository.findAll().stream()
                .filter(item -> item != null)
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> sameText(item.getStudentEmail(), normalizedStudentEmail))
                .filter(item -> normalizedFilterExamId == null || sameText(item.getExamId(), normalizedFilterExamId))
                .filter(item -> normalizedFilterExamId != null || filterExamName == null || sameText(item.getExamName(), filterExamName))
                .filter(item -> filterActivityType == null || sameText(item.getActivityType(), filterActivityType))
                .filter(item -> filterTimeLimit == null || filterTimeLimit.equals(item.getTimeLimit()))
                .filter(item -> {
                    String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                    return normalizedDeadline.isBlank() || itemDeadline.equals(normalizedDeadline);
                })
                .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
        }

        if (source == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "No distributed quiz record found for this student.");
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                normalizedFilterExamId,
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                distributionId);
        }

        LocalDateTime newDeadlineAt;
        try {
            newDeadlineAt = resolveReopenDeadlineAt(reopenPreset, customDeadline);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:" + buildDistributionStudentsReturnTo(
                id,
                source.getExamId(),
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                source.getId());
        }
        String newDeadlineValue = newDeadlineAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));

        applyReopenToExistingExam(source, newDeadlineValue, LocalDateTime.now());
        distributedExamRepository.save(source);

        redirectAttributes.addFlashAttribute("successMessage",
            "Quiz reopened for " + normalizedStudentEmail + " until " + newDeadlineAt.format(DEADLINE_DISPLAY_FORMAT) + ".");
        return "redirect:" + buildDistributionStudentsReturnTo(
            id,
            source.getExamId(),
            source.getExamName(),
            source.getActivityType(),
            source.getTimeLimit(),
            newDeadlineValue,
            source.getId());
    }

    @GetMapping("/subject-classroom/{id}/distribution-students/reopen")
    public String reopenDistributedQuizForStudentGet(@PathVariable("id") Long id,
                                                     @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                                     @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                                     @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                                     @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                                     @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                                     @RequestParam(name = "distributionId", required = false) Long distributionId,
                                                     RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("errorMessage", "Use the Re-open button to submit a custom retake schedule.");
        return "redirect:" + buildDistributionStudentsReturnTo(
            id,
            filterExamId,
            filterExamName,
            filterActivityType,
            filterTimeLimit,
            filterDeadline,
            distributionId);
    }

    @PostMapping("/subject-classroom/{id}/distributed-exams/reopen")
    public String reopenDistributedQuizBatch(@PathVariable("id") Long id,
                                             @RequestParam(name = "distributionId", required = false) Long distributionId,
                                             @RequestParam(name = "examId", required = false) String examId,
                                             @RequestParam("examName") String examName,
                                             @RequestParam("activityType") String activityType,
                                             @RequestParam("timeLimit") Integer timeLimit,
                                             @RequestParam(name = "deadline", required = false) String deadline,
                                             @RequestParam(name = "reopenPreset", required = false, defaultValue = "24h") String reopenPreset,
                                             @RequestParam(name = "customDeadline", required = false) String customDeadline,
                                             Principal principal,
                                             RedirectAttributes redirectAttributes) {
        Optional<Subject> subjectOpt = subjectRepository.findById(id);
        if (subjectOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Subject not found.");
            return "redirect:/teacher/subjects";
        }

        Subject subject = subjectOpt.get();
        if (!isOwner(principal, subject.getTeacherEmail())) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to reopen this quiz batch.");
            return "redirect:/teacher/subjects";
        }

        LocalDateTime newDeadlineAt;
        try {
            newDeadlineAt = resolveReopenDeadlineAt(reopenPreset, customDeadline);
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
            return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
        }

        List<DistributedExam> matchingBatch;
        if (distributionId != null) {
            matchingBatch = distributedExamRepository.findById(distributionId)
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> !item.isSubmitted())
                .stream()
                .toList();
        } else {
            String normalizedDeadline = deadline == null ? "" : deadline.trim();
            matchingBatch = distributedExamRepository.findAll().stream()
                .filter(item -> item != null)
                .filter(item -> sameText(item.getSubject(), subject.getSubjectName()))
                .filter(item -> examId == null || examId.isBlank() || sameText(item.getExamId(), examId))
                .filter(item -> (examId != null && !examId.isBlank()) || sameText(item.getExamName(), examName))
                .filter(item -> sameText(item.getActivityType(), activityType))
                .filter(item -> timeLimit != null && timeLimit.equals(item.getTimeLimit()))
                .filter(item -> {
                    String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                    return itemDeadline.equals(normalizedDeadline);
                })
                .filter(item -> !item.isSubmitted())
                .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        }

        if (matchingBatch.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No missed or pending students found for this quiz batch.");
            return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
        }

        Map<String, DistributedExam> latestByStudent = new LinkedHashMap<>();
        for (DistributedExam item : matchingBatch) {
            String emailKey = item.getStudentEmail() == null ? "" : item.getStudentEmail().trim().toLowerCase();
            if (emailKey.isBlank() || latestByStudent.containsKey(emailKey)) {
                continue;
            }
            latestByStudent.put(emailKey, item);
        }

        String newDeadlineValue = newDeadlineAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        int reopenedCount = 0;
        for (DistributedExam source : latestByStudent.values()) {
            applyReopenToExistingExam(source, newDeadlineValue, LocalDateTime.now());
            distributedExamRepository.save(source);
            reopenedCount++;
        }

        redirectAttributes.addFlashAttribute("successMessage",
            "Re-opened quiz for " + reopenedCount + " student(s) until " + newDeadlineAt.format(DEADLINE_DISPLAY_FORMAT) + ".");
        return "redirect:/teacher/subject-classroom/" + id + "/distributed-exams";
    }

    private LocalDateTime resolveReopenDeadlineAt(String reopenPreset, String customDeadline) {
        LocalDateTime now = LocalDateTime.now();
        String normalizedPreset = reopenPreset == null ? "24h" : reopenPreset.trim().toLowerCase();

        switch (normalizedPreset) {
            case "1h":
                return now.plusHours(1);
            case "72h":
                return now.plusHours(72);
            case "custom":
                LocalDateTime parsedCustom = parseDeadlineValue(customDeadline);
                if (parsedCustom == null) {
                    throw new IllegalArgumentException("Please provide a valid custom date and time.");
                }
                if (!parsedCustom.isAfter(now)) {
                    throw new IllegalArgumentException("Custom deadline must be in the future.");
                }
                return parsedCustom;
            case "24h":
            default:
                return now.plusHours(24);
        }
    }

    private void applyReopenToExistingExam(DistributedExam target,
                                           String deadlineValue,
                                           LocalDateTime distributedAt) {
        if (target == null) {
            return;
        }

        target.setDeadline(deadlineValue == null ? "" : deadlineValue);
        target.setDistributedAt(distributedAt == null ? LocalDateTime.now() : distributedAt);
        target.setSubmitted(false);
        clearDistributionOtpState(target);
    }

    private String generateNumericOtpCode(int length) {
        int safeLength = Math.max(4, length);
        StringBuilder builder = new StringBuilder(safeLength);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int index = 0; index < safeLength; index++) {
            builder.append(random.nextInt(0, 10));
        }
        return builder.toString();
    }

    private String buildOtpStatusLabel(DistributedExam distribution, LocalDateTime now) {
        if (distribution == null) {
            return "Not generated";
        }

        if (distribution.isSubmitted()) {
            return "Closed";
        }

        if (distribution.getAccessOtpVerifiedAt() != null) {
            return "Verified";
        }

        String hash = distribution.getAccessOtpHash() == null ? "" : distribution.getAccessOtpHash().trim();
        if (hash.isBlank()) {
            return "Not generated";
        }

        LocalDateTime expiresAt = distribution.getAccessOtpExpiresAt();
        if (expiresAt != null && (now == null || expiresAt.isAfter(now))) {
            return "Active until " + expiresAt.format(DEADLINE_DISPLAY_FORMAT);
        }
        return "Expired";
    }

    private String buildOtpStatusClass(DistributedExam distribution, LocalDateTime now) {
        if (distribution == null || distribution.isSubmitted()) {
            return "bg-secondary";
        }

        if (distribution.getAccessOtpVerifiedAt() != null) {
            return "bg-success";
        }

        String hash = distribution.getAccessOtpHash() == null ? "" : distribution.getAccessOtpHash().trim();
        if (hash.isBlank()) {
            return "bg-secondary";
        }

        LocalDateTime expiresAt = distribution.getAccessOtpExpiresAt();
        if (expiresAt != null && (now == null || expiresAt.isAfter(now))) {
            return "bg-warning text-dark";
        }
        return "bg-danger";
    }

    private String resolveOtpActionLabel(DistributedExam distribution, LocalDateTime now) {
        if (distribution == null || distribution.isSubmitted()) {
            return "N/A";
        }

        if (distribution.getAccessOtpVerifiedAt() != null) {
            return "Generate New OTP";
        }

        String hash = distribution.getAccessOtpHash() == null ? "" : distribution.getAccessOtpHash().trim();
        if (hash.isBlank()) {
            return "Generate OTP";
        }

        LocalDateTime expiresAt = distribution.getAccessOtpExpiresAt();
        if (expiresAt != null && (now == null || expiresAt.isAfter(now))) {
            return "Regenerate OTP";
        }
        return "Generate New OTP";
    }

    private void clearDistributionOtpState(DistributedExam target) {
        if (target == null) {
            return;
        }

        target.setAccessOtpHash(null);
        target.setAccessOtpGeneratedAt(null);
        target.setAccessOtpExpiresAt(null);
        target.setAccessOtpVerifiedAt(null);
    }

    private String buildDistributionStudentsReturnTo(Long subjectId,
                                                     String filterExamId,
                                                     String filterExamName,
                                                     String filterActivityType,
                                                     Integer filterTimeLimit,
                                                     String filterDeadline,
                                                     Long distributionId) {
        StringBuilder target = new StringBuilder("/teacher/subject-classroom/")
            .append(subjectId)
            .append("/distribution-students");

        List<String> query = new ArrayList<>();
        appendQueryParam(query, "filterExamId", filterExamId);
        appendQueryParam(query, "filterExamName", filterExamName);
        appendQueryParam(query, "filterActivityType", filterActivityType);
        if (filterTimeLimit != null) {
            query.add("filterTimeLimit=" + filterTimeLimit);
        }
        appendQueryParam(query, "filterDeadline", filterDeadline);
        if (distributionId != null) {
            query.add("distributionId=" + distributionId);
        }

        if (!query.isEmpty()) {
            target.append("?").append(String.join("&", query));
        }
        return target.toString();
    }

    @GetMapping("/view-result/{id}")
    public String viewResult(@PathVariable("id") Long id,
                             @RequestParam(name = "returnTo", required = false) String returnTo,
                             Model model,
                             Principal principal) {
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty()) {
            return "redirect:/teacher/subjects";
        }

        ExamSubmission submission = submissionOpt.get();
        if (!canTeacherAccessSubmission(submission, principal)) {
            return "redirect:/teacher/subjects";
        }

        List<Map<String, Object>> answerDetails = extractAnswerDetails(submission.getAnswerDetailsJson());
        int correctAnswers = (int) answerDetails.stream().filter(this::isCorrectAnswerDetail).count();
        int incorrectAnswers = Math.max(0, answerDetails.size() - correctAnswers);

        model.addAttribute("submission", submission);
        model.addAttribute("answerDetails", answerDetails);
        model.addAttribute("correctAnswers", correctAnswers);
        model.addAttribute("incorrectAnswers", incorrectAnswers);
        model.addAttribute("returnTo", resolveTeacherReturnTo(returnTo, "/teacher/subjects"));
        return "teacher-view-student-result";
    }

    @GetMapping("/grade/{id}")
    public String gradeSubmission(@PathVariable("id") Long id,
                                  @RequestParam(name = "returnTo", required = false) String returnTo,
                                  Model model,
                                  Principal principal,
                                  RedirectAttributes redirectAttributes) {
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Submission not found.");
            return "redirect:/teacher/subjects";
        }

        ExamSubmission submission = submissionOpt.get();
        if (!canTeacherAccessSubmission(submission, principal)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to grade this submission.");
            return "redirect:/teacher/subjects";
        }

        List<Map<String, Object>> answerDetails = extractAnswerDetails(submission.getAnswerDetailsJson());
        Map<Integer, Integer> manualPerQuestion = extractManualPerQuestion(submission.getAnswerDetailsJson());

        int textInputCount = 0;
        int manualFromPerQuestion = 0;
        for (int index = 0; index < answerDetails.size(); index++) {
            Map<String, Object> detail = answerDetails.get(index);
            int questionNumber = extractInt(detail.get("questionNumber"), index + 1);
            boolean openEnded = isOpenEndedAnswerDetail(detail);
            if (openEnded) {
                textInputCount++;
            }

            int manualPoints = manualPerQuestion.getOrDefault(questionNumber, 0);
            manualFromPerQuestion += manualPoints;

            detail.put("questionNumber", questionNumber);
            detail.put("isTextInput", openEnded);
            detail.put("needsManualGrade", openEnded);
            detail.put("manualPoints", manualPoints);
        }

        int currentManualScore = submission.getManualScore() != null
            ? Math.max(0, submission.getManualScore())
            : Math.max(0, manualFromPerQuestion);
        int maxManualScore = Math.max(1, submission.getTotalQuestions());

        model.addAttribute("submission", submission);
        model.addAttribute("answerDetails", answerDetails);
        model.addAttribute("currentManualScore", currentManualScore);
        model.addAttribute("textInputCount", textInputCount);
        model.addAttribute("maxManualScore", maxManualScore);
        model.addAttribute("returnTo", resolveTeacherReturnTo(returnTo, "/teacher/view-result/" + submission.getId()));
        return "teacher-grade-exam";
    }

    @PostMapping("/finalize-grade")
    public String finalizeGrade(@RequestParam Map<String, String> formData,
                                Principal principal,
                                RedirectAttributes redirectAttributes) {
        Long submissionId = parseLongSafe(formData.get("submissionId"));
        if (submissionId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Invalid submission id.");
            return "redirect:/teacher/subjects";
        }

        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(submissionId);
        if (submissionOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Submission not found.");
            return "redirect:/teacher/subjects";
        }

        ExamSubmission submission = submissionOpt.get();
        if (!canTeacherAccessSubmission(submission, principal)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to grade this submission.");
            return "redirect:/teacher/subjects";
        }

        List<Map<String, Object>> answerDetails = extractAnswerDetails(submission.getAnswerDetailsJson());
        Map<Integer, Integer> manualPerQuestion = new HashMap<>();
        int totalManualScore = 0;
        int manualMaxPerQuestion = Math.max(1, submission.getTotalQuestions());

        for (int index = 0; index < answerDetails.size(); index++) {
            Map<String, Object> detail = answerDetails.get(index);
            int questionNumber = extractInt(detail.get("questionNumber"), index + 1);
            if (!isOpenEndedAnswerDetail(detail)) {
                continue;
            }

            int manualPoints = clampInt(parseIntSafe(formData.get("manual_q_" + questionNumber), 0), 0, manualMaxPerQuestion);
            manualPerQuestion.put(questionNumber, manualPoints);
            totalManualScore += manualPoints;
            detail.put("manualPoints", manualPoints);
            detail.put("needsManualGrade", false);
            detail.put("isTextInput", true);
        }

        if (manualPerQuestion.isEmpty()) {
            totalManualScore = clampInt(parseIntSafe(formData.get("manualScore"), 0), 0, submission.getTotalQuestions());
        } else {
            totalManualScore = clampInt(totalManualScore, 0, submission.getTotalQuestions());
        }

        submission.setManualScore(totalManualScore);
        submission.setGraded(Boolean.TRUE);
        submission.setGradedAt(LocalDateTime.now());
        submission.setTeacherComments(formData.getOrDefault("teacherComments", "").trim());

        String releaseOption = formData.getOrDefault("releaseOption", "FINALIZE_ONLY");
        boolean releaseNow = "RELEASE_TO_STUDENT".equalsIgnoreCase(releaseOption);
        submission.setResultsReleased(releaseNow);
        submission.setReleasedAt(releaseNow ? LocalDateTime.now() : null);

        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        payload.put("finalAnswers", answerDetails);
        payload.put("manualPerQuestion", manualPerQuestion);
        payload.put("manualScore", totalManualScore);
        payload.put("gradedAt", submission.getGradedAt() == null ? null : submission.getGradedAt().toString());
        submission.setAnswerDetailsJson(gson.toJson(payload));

        examSubmissionRepository.save(submission);

        if (releaseNow) {
            redirectAttributes.addFlashAttribute("successMessage", "Grade finalized and released to student.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Grade finalized. Result remains hidden until release.");
        }

        String returnTo = resolveTeacherReturnTo(formData.get("returnTo"), "/teacher/subjects");
        return "redirect:/teacher/view-result/" + submission.getId() + buildReturnToQuery(returnTo);
    }

    @PostMapping("/toggle-result-release/{id}")
    public String toggleResultRelease(@PathVariable("id") Long id,
                                      @RequestParam(name = "redirectTo", required = false) String redirectTo,
                                      @RequestParam(name = "returnTo", required = false) String returnTo,
                                      @RequestParam(name = "subjectId", required = false) Long subjectId,
                                      @RequestParam(name = "filterExamId", required = false) String filterExamId,
                                      @RequestParam(name = "filterExamName", required = false) String filterExamName,
                                      @RequestParam(name = "filterActivityType", required = false) String filterActivityType,
                                      @RequestParam(name = "filterTimeLimit", required = false) Integer filterTimeLimit,
                                      @RequestParam(name = "filterDeadline", required = false) String filterDeadline,
                                      @RequestParam(name = "distributionId", required = false) Long distributionId,
                                      Principal principal,
                                      RedirectAttributes redirectAttributes) {
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Submission not found.");
            return buildReleaseRedirect(
                redirectTo,
                id,
                returnTo,
                subjectId,
                filterExamId,
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                distributionId);
        }

        ExamSubmission submission = submissionOpt.get();
        if (!canTeacherAccessSubmission(submission, principal)) {
            redirectAttributes.addFlashAttribute("errorMessage", "You are not allowed to update this submission.");
            return "redirect:/teacher/subjects";
        }

        boolean isCurrentlyReleased = submission.isResultsReleased();
        if (!isCurrentlyReleased && hasOpenEndedQuestions(submission) && !submission.isGraded()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                "Complete open-ended grading first before releasing this result.");
            return buildReleaseRedirect(
                redirectTo,
                id,
                returnTo,
                subjectId,
                filterExamId,
                filterExamName,
                filterActivityType,
                filterTimeLimit,
                filterDeadline,
                distributionId);
        }

        submission.setResultsReleased(!isCurrentlyReleased);
        submission.setReleasedAt(submission.isResultsReleased() ? LocalDateTime.now() : null);
        examSubmissionRepository.save(submission);

        redirectAttributes.addFlashAttribute("successMessage",
            submission.isResultsReleased()
                ? "Result released to student."
                : "Result hidden from student.");
        return buildReleaseRedirect(
            redirectTo,
            id,
            returnTo,
            subjectId,
            filterExamId,
            filterExamName,
            filterActivityType,
            filterTimeLimit,
            filterDeadline,
            distributionId);
    }

    @GetMapping("/profile")
    public String profile() {
        return "teacher-profile";
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase().trim();
    }

    private boolean sameText(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private List<Map<String, Object>> buildQuizDistributionSummary(List<DistributedExam> distributedExams) {
        List<DistributedExam> sorted = new ArrayList<>(distributedExams == null ? new ArrayList<>() : distributedExams);
        sorted.sort(Comparator.comparing(DistributedExam::getDistributedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Map<String, DistributedExam>> latestByBatchAndStudent = new LinkedHashMap<>();
        for (DistributedExam distributedExam : sorted) {
            if (distributedExam == null || distributedExam.getExamName() == null || distributedExam.getExamName().isBlank()) {
                continue;
            }

            String batchKey = buildDistributionBatchKey(distributedExam);
            String studentKey = normalize(distributedExam.getStudentEmail());
            if (batchKey.isBlank() || studentKey.isBlank()) {
                continue;
            }

            latestByBatchAndStudent
                .computeIfAbsent(batchKey, ignored -> new LinkedHashMap<>())
                .putIfAbsent(studentKey, distributedExam);
        }

        List<Map<String, Object>> summaryRows = new ArrayList<>();
        for (Map<String, DistributedExam> studentMap : latestByBatchAndStudent.values()) {
            if (studentMap == null || studentMap.isEmpty()) {
                continue;
            }

            List<DistributedExam> batchRows = new ArrayList<>(studentMap.values());
            batchRows.sort(Comparator.comparing(DistributedExam::getDistributedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

            DistributedExam distributedExam = batchRows.get(0);

            String deadlineRaw = distributedExam.getDeadline() == null ? "" : distributedExam.getDeadline();
            Map<String, Object> row = new HashMap<>();
            Integer safeTimeLimit = distributedExam.getTimeLimit();
            int assignedCount = batchRows.size();
            int submittedCount = (int) batchRows.stream().filter(DistributedExam::isSubmitted).count();
            int notSubmitted = assignedCount - submittedCount;

            row.put("distributionId", distributedExam.getId());
            row.put("examId", distributedExam.getExamId());
            row.put("examName", distributedExam.getExamName());
            row.put("subject", distributedExam.getSubject() == null ? "" : distributedExam.getSubject());
            row.put("activityType", distributedExam.getActivityType() == null ? "Quiz" : distributedExam.getActivityType());
            row.put("timeLimit", safeTimeLimit != null ? safeTimeLimit : 60);
            row.put("deadline", formatDeadline(deadlineRaw));
            row.put("filterExamId", distributedExam.getExamId());
            row.put("filterExamName", distributedExam.getExamName());
            row.put("filterActivityType", distributedExam.getActivityType());
            row.put("filterTimeLimit", distributedExam.getTimeLimit());
            row.put("filterDeadline", deadlineRaw == null ? "" : deadlineRaw);
            row.put("assignedCount", assignedCount);
            row.put("submittedCount", submittedCount);
            row.put("notSubmittedCount", notSubmitted);

            boolean completed = submittedCount >= assignedCount;
            LocalDateTime deadlineAt = parseDeadlineValue(String.valueOf(row.getOrDefault("filterDeadline", "")));
            boolean overdue = !completed && notSubmitted > 0 && deadlineAt != null && LocalDateTime.now().isAfter(deadlineAt);
            boolean ongoing = false;
            boolean waiting = !completed && !overdue;

            row.put("completed", completed);
            row.put("overdue", overdue);
            row.put("ongoing", ongoing);
            row.put("waiting", waiting);

            if (completed) {
                row.put("statusLabel", "Completed");
                row.put("statusClass", "bg-success");
            } else if (overdue) {
                row.put("statusLabel", "Overdue");
                row.put("statusClass", "bg-danger");
            } else if (submittedCount > 0) {
                row.put("statusLabel", "Ongoing");
                row.put("statusClass", "bg-primary");
            } else {
                row.put("statusLabel", "Waiting for submissions");
                row.put("statusClass", "bg-secondary");
            }

            summaryRows.add(row);
        }

        return summaryRows;
    }

    private String buildDistributionBatchKey(DistributedExam distributedExam) {
        if (distributedExam == null) {
            return "";
        }

        String subjectKey = normalize(distributedExam.getSubject());
        String examIdentity = normalize(distributedExam.getExamId()).isBlank()
            ? "name:" + normalize(distributedExam.getExamName())
            : "id:" + normalize(distributedExam.getExamId());
        String activityKey = normalize(distributedExam.getActivityType());
        String deadlineKey = normalize(distributedExam.getDeadline());
        String timeLimitKey = String.valueOf(distributedExam.getTimeLimit() == null ? 60 : distributedExam.getTimeLimit());

        return String.join("|", subjectKey, examIdentity, activityKey, timeLimitKey, deadlineKey);
    }

    private String formatDeadline(String deadlineRaw) {
        if (deadlineRaw == null || deadlineRaw.isBlank()) {
            return "No deadline";
        }
        LocalDateTime parsedDeadline = parseDeadlineValue(deadlineRaw);
        if (parsedDeadline != null) {
            return parsedDeadline.format(DEADLINE_DISPLAY_FORMAT);
        }
        return deadlineRaw;
    }

    private LocalDateTime parseDeadlineValue(String deadlineRaw) {
        if (deadlineRaw == null || deadlineRaw.isBlank()) {
            return null;
        }

        String normalized = deadlineRaw.trim();
        List<DateTimeFormatter> acceptedFormats = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DEADLINE_DISPLAY_FORMAT
        );

        for (DateTimeFormatter formatter : acceptedFormats) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (Exception ignored) {
            }
        }

        if (normalized.contains(" ") && !normalized.contains("T")) {
            try {
                return LocalDateTime.parse(normalized.replace(" ", "T"));
            } catch (Exception ignored) {
            }
        }

        return null;
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

    private String generateStudentPassword() {
        return DEFAULT_IMPORTED_STUDENT_PASSWORD;
    }

    private boolean isSubmissionCompleted(ExamSubmission submission) {
        if (submission == null) {
            return false;
        }
        if (submission.isGraded()) {
            return true;
        }
        if (submission.getScore() > 0 || submission.getPercentage() > 0) {
            return true;
        }

        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        Object submittedFlag = payload.get("submitted");
        if (submittedFlag instanceof Boolean bool && bool) {
            return true;
        }

        Object studentAnswers = payload.get("studentAnswers");
        if (studentAnswers instanceof List<?> list && !list.isEmpty()) {
            return true;
        }

        Object finalAnswers = payload.get("finalAnswers");
        return finalAnswers instanceof List<?> list && !list.isEmpty();
    }

    private Map<String, Object> parseFlexibleMapJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = gson.fromJson(json,
                new TypeToken<Map<String, Object>>() { }.getType());
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception exception) {
            return new HashMap<>();
        }
    }

    private ExamSubmission findLatestSubmissionForDistributedExam(DistributedExam distributedExam) {
        if (distributedExam == null
            || distributedExam.getStudentEmail() == null
            || distributedExam.getStudentEmail().isBlank()
            || distributedExam.getExamName() == null
            || distributedExam.getExamName().isBlank()
            || distributedExam.getSubject() == null
            || distributedExam.getSubject().isBlank()) {
            return null;
        }

        List<ExamSubmission> candidates = examSubmissionRepository
            .findByStudentEmailAndExamNameAndSubject(
                distributedExam.getStudentEmail(),
                distributedExam.getExamName(),
                distributedExam.getSubject());
        if (candidates.isEmpty()) {
            return null;
        }

        Long distributionId = distributedExam.getId();
        if (distributionId != null) {
            Optional<ExamSubmission> exactMatch = candidates.stream()
                .filter(submission -> distributionId.equals(extractSubmissionDistributedExamId(submission)))
                .max(Comparator.comparing(ExamSubmission::getSubmittedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            if (exactMatch.isPresent()) {
                return exactMatch.get();
            }
        }

        String distributionDeadline = distributedExam.getDeadline() == null ? "" : distributedExam.getDeadline().trim();
        if (!distributionDeadline.isBlank()) {
            Optional<ExamSubmission> deadlineMatch = candidates.stream()
                .filter(submission -> sameText(extractSubmissionDeadline(submission), distributionDeadline))
                .max(Comparator.comparing(ExamSubmission::getSubmittedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            if (deadlineMatch.isPresent()) {
                return deadlineMatch.get();
            }
        }

        if (!distributedExam.isSubmitted()) {
            return null;
        }

        return candidates.stream()
            .max(Comparator.comparing(ExamSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);
    }

    private Long extractSubmissionDistributedExamId(ExamSubmission submission) {
        if (submission == null) {
            return null;
        }
        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        Object raw = payload.get("distributedExamId");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return raw == null ? null : parseLongSafe(String.valueOf(raw));
    }

    private String extractSubmissionDeadline(ExamSubmission submission) {
        if (submission == null) {
            return "";
        }
        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        Object rawDeadline = payload.get("deadline");
        return rawDeadline == null ? "" : String.valueOf(rawDeadline).trim();
    }

    private String extractSubmissionExamId(ExamSubmission submission) {
        if (submission == null) {
            return "";
        }
        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        Object rawExamId = payload.get("examId");
        return rawExamId == null ? "" : String.valueOf(rawExamId).trim();
    }

    private String buildReleaseRedirect(String redirectTo,
                                        Long submissionId,
                                        String returnTo,
                                        Long subjectId,
                                        String filterExamId,
                                        String filterExamName,
                                        String filterActivityType,
                                        Integer filterTimeLimit,
                                        String filterDeadline,
                                        Long distributionId) {
        if ("detail".equalsIgnoreCase(redirectTo) && submissionId != null) {
            return "redirect:/teacher/view-result/" + submissionId
                + buildReturnToQuery(resolveTeacherReturnTo(returnTo, null));
        }

        if ("distribution".equalsIgnoreCase(redirectTo) && subjectId != null) {
            StringBuilder target = new StringBuilder("redirect:/teacher/subject-classroom/")
                .append(subjectId)
                .append("/distribution-students");

            List<String> query = new ArrayList<>();
            appendQueryParam(query, "filterExamId", filterExamId);
            appendQueryParam(query, "filterExamName", filterExamName);
            appendQueryParam(query, "filterActivityType", filterActivityType);
            if (filterTimeLimit != null) {
                query.add("filterTimeLimit=" + filterTimeLimit);
            }
            appendQueryParam(query, "filterDeadline", filterDeadline);
            if (distributionId != null) {
                query.add("distributionId=" + distributionId);
            }

            if (!query.isEmpty()) {
                target.append("?").append(String.join("&", query));
            }
            return target.toString();
        }

        if ("submissions".equalsIgnoreCase(redirectTo)) {
            return "redirect:/teacher/submissions";
        }

        return "redirect:/teacher/subjects";
    }

    private String resolveTeacherReturnTo(String returnTo, String fallback) {
        if (returnTo == null || returnTo.isBlank()) {
            return fallback;
        }
        String normalized = returnTo.trim();
        if (normalized.startsWith("/teacher/")) {
            return normalized;
        }
        return fallback;
    }

    private void appendQueryParam(List<String> query, String key, String value) {
        if (query == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        query.add(key + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private boolean canTeacherAccessSubmission(ExamSubmission submission, Principal principal) {
        if (submission == null || principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return false;
        }
        String teacherEmail = principal.getName();
        String subjectName = submission.getSubject() == null ? "" : submission.getSubject().trim();
        if (subjectName.isBlank()) {
            return false;
        }

        return subjectRepository.findByTeacherEmail(teacherEmail).stream()
            .map(Subject::getSubjectName)
            .filter(name -> name != null && !name.isBlank())
            .anyMatch(name -> name.equalsIgnoreCase(subjectName));
    }

    private boolean hasOpenEndedQuestions(ExamSubmission submission) {
        if (submission == null) {
            return false;
        }
        return extractAnswerDetails(submission.getAnswerDetailsJson()).stream()
            .anyMatch(this::isOpenEndedAnswerDetail);
    }

    private List<Map<String, Object>> extractAnswerDetails(String answerDetailsJson) {
        if (answerDetailsJson == null || answerDetailsJson.isBlank()) {
            return new ArrayList<>();
        }

        try {
            Map<String, Object> payload = gson.fromJson(answerDetailsJson,
                new TypeToken<Map<String, Object>>() { }.getType());
            Object finalAnswers = payload == null ? null : payload.get("finalAnswers");
            if (finalAnswers instanceof List<?> list) {
                return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .collect(Collectors.toList());
            }
        } catch (Exception ignored) {
        }

        return new ArrayList<>();
    }

    private Map<Integer, Integer> extractManualPerQuestion(String answerDetailsJson) {
        Map<Integer, Integer> manualPerQuestion = new HashMap<>();
        Map<String, Object> payload = parseFlexibleMapJson(answerDetailsJson);
        Object raw = payload.get("manualPerQuestion");
        if (!(raw instanceof Map<?, ?> map)) {
            return manualPerQuestion;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Integer questionNumber = parseIntSafe(entry.getKey() == null ? null : String.valueOf(entry.getKey()), -1);
            if (questionNumber == null || questionNumber <= 0) {
                continue;
            }
            Integer points = null;
            if (entry.getValue() instanceof Number number) {
                points = number.intValue();
            } else if (entry.getValue() != null) {
                points = parseIntSafe(String.valueOf(entry.getValue()), 0);
            }
            manualPerQuestion.put(questionNumber, Math.max(0, points == null ? 0 : points));
        }

        return manualPerQuestion;
    }

    private boolean isOpenEndedAnswerDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }

        String type = String.valueOf(detail.getOrDefault("type", "")).trim();
        String correctAnswer = String.valueOf(detail.getOrDefault("correctAnswer", "")).trim();
        String questionText = String.valueOf(detail.getOrDefault("question", "")).trim();

        if ("OPEN_ENDED".equalsIgnoreCase(type)) {
            return true;
        }

        if ("MANUAL_GRADE".equalsIgnoreCase(correctAnswer)) {
            return true;
        }

        return questionText.startsWith("[TEXT_INPUT]");
    }

    private boolean isCorrectAnswerDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return false;
        }
        Object value = detail.get("isCorrect");
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private int extractInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Long parseLongSafe(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer parseIntSafe(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int clampInt(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private List<Integer> selectQuestionIndexesByDifficulty(int requestedCount,
                                                            int easyPercent,
                                                            int mediumPercent,
                                                            int hardPercent,
                                                            Map<String, String> difficultiesMap,
                                                            int totalQuestions) {
        List<Integer> easyPool = new ArrayList<>();
        List<Integer> mediumPool = new ArrayList<>();
        List<Integer> hardPool = new ArrayList<>();

        for (int index = 0; index < totalQuestions; index++) {
            String raw = difficultiesMap.getOrDefault(String.valueOf(index + 1), "Medium");
            String normalized = normalizeDifficulty(raw);
            if ("Easy".equalsIgnoreCase(normalized)) {
                easyPool.add(index);
            } else if ("Hard".equalsIgnoreCase(normalized)) {
                hardPool.add(index);
            } else {
                mediumPool.add(index);
            }
        }

        fisherYatesService.shuffle(easyPool);
        fisherYatesService.shuffle(mediumPool);
        fisherYatesService.shuffle(hardPool);

        int easyTarget = Math.round(requestedCount * (easyPercent / 100f));
        int mediumTarget = Math.round(requestedCount * (mediumPercent / 100f));
        int hardTarget = Math.max(0, requestedCount - easyTarget - mediumTarget);

        List<Integer> selected = new ArrayList<>();
        selected.addAll(takeFromPool(easyPool, easyTarget));
        selected.addAll(takeFromPool(mediumPool, mediumTarget));
        selected.addAll(takeFromPool(hardPool, hardTarget));

        if (selected.size() < requestedCount) {
            List<Integer> remaining = new ArrayList<>();
            remaining.addAll(easyPool);
            remaining.addAll(mediumPool);
            remaining.addAll(hardPool);
            fisherYatesService.shuffle(remaining);

            int needed = requestedCount - selected.size();
            selected.addAll(takeFromPool(remaining, needed));
        }

        fisherYatesService.shuffle(selected);
        return selected;
    }

    private List<Integer> buildUniqueQuestionOrder(List<Integer> baseIndexes, Set<String> usedOrders) {
        if (baseIndexes == null || baseIndexes.isEmpty()) {
            return new ArrayList<>();
        }

        List<Integer> candidate = new ArrayList<>(baseIndexes);
        fisherYatesService.shuffle(candidate);
        if (usedOrders == null) {
            return candidate;
        }

        String signature = candidate.toString();
        if (usedOrders.add(signature)) {
            return candidate;
        }

        int maxAttempts = Math.max(4, baseIndexes.size() * 2);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            fisherYatesService.shuffle(candidate);
            signature = candidate.toString();
            if (usedOrders.add(signature)) {
                return candidate;
            }
        }

        for (int shift = 1; shift < baseIndexes.size(); shift++) {
            List<Integer> rotated = new ArrayList<>(baseIndexes.size());
            for (int index = 0; index < baseIndexes.size(); index++) {
                rotated.add(baseIndexes.get((index + shift) % baseIndexes.size()));
            }
            signature = rotated.toString();
            if (usedOrders.add(signature)) {
                return rotated;
            }
        }

        return new ArrayList<>(baseIndexes);
    }

    private List<Integer> takeFromPool(List<Integer> pool, int count) {
        if (count <= 0 || pool.isEmpty()) {
            return new ArrayList<>();
        }
        int take = Math.min(count, pool.size());
        List<Integer> picked = new ArrayList<>(pool.subList(0, take));
        pool.subList(0, take).clear();
        return picked;
    }

    private String deriveExamName(String quizName, String originalFilename) {
        if (quizName != null && !quizName.trim().isEmpty()) {
            return quizName.trim();
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            return "Processed Exam";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        return dotIndex > 0 ? originalFilename.substring(0, dotIndex) : originalFilename;
    }

    private String ensureUniqueExamNameForTeacher(String teacherEmail, String requestedName) {
        String baseName = requestedName == null ? "" : requestedName.trim();
        if (baseName.isBlank()) {
            baseName = "Processed Exam";
        }

        Set<String> existingNames = findTeacherProcessedPapers(teacherEmail).stream()
            .map(OriginalProcessedPaper::getExamName)
            .map(this::normalize)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toSet());

        if (!existingNames.contains(normalize(baseName))) {
            return baseName;
        }

        int suffix = 2;
        while (suffix < 10_000) {
            String candidate = baseName + " (" + suffix + ")";
            if (!existingNames.contains(normalize(candidate))) {
                return candidate;
            }
            suffix++;
        }

        return baseName + " (" + UUID.randomUUID().toString().substring(0, 8) + ")";
    }

    private List<Map<String, Object>> parseExamFile(MultipartFile examFile) throws IOException {
        String filename = examFile.getOriginalFilename() == null ? "" : examFile.getOriginalFilename().toLowerCase();
        if (filename.endsWith(".csv")) {
            return parseQuestionsFromCsv(examFile);
        }
        return parseQuestionsFromPdf(examFile);
    }

    private List<Map<String, Object>> parseQuestionsFromCsv(MultipartFile csvFile) throws IOException {
        List<Map<String, Object>> questions = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(csvFile.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean headerParsed = false;
            int questionColumn = -1;
            int answerColumn = -1;
            int difficultyColumn = -1;
            List<Integer> choiceColumns = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                List<String> columns = splitCsvLine(line);
                if (!headerParsed) {
                    headerParsed = true;

                    List<String> canonicalHeaders = new ArrayList<>();
                    for (String col : columns) {
                        canonicalHeaders.add(canonicalizeCsvHeader(col));
                    }

                    questionColumn = findCsvColumn(canonicalHeaders, "questiontext", "question", "questiontitle");
                    answerColumn = findCsvColumn(canonicalHeaders, "correctanswer", "answer", "key");
                    difficultyColumn = findCsvColumn(canonicalHeaders, "difficulty", "level");

                    for (int index = 0; index < canonicalHeaders.size(); index++) {
                        String header = canonicalHeaders.get(index);
                        if (header.startsWith("choice") || header.startsWith("option")) {
                            choiceColumns.add(index);
                        }
                    }

                    if (questionColumn < 0 || answerColumn < 0 || choiceColumns.isEmpty()) {
                        throw new IllegalArgumentException(
                            "Unsupported CSV header. Use: Difficulty,Question_Text,Choice_A...Choice_Z,Correct_Answer");
                    }

                    continue;
                }

                if (columns.isEmpty() || questionColumn < 0 || questionColumn >= columns.size()) {
                    continue;
                }

                Map<String, Object> question = new HashMap<>();
                String questionText = columns.get(questionColumn) == null ? "" : columns.get(questionColumn).trim();
                questionText = normalizeQuestionHtml(questionText);
                if (questionText.isBlank()) {
                    continue;
                }

                List<String> choices = new ArrayList<>();
                for (Integer index : choiceColumns) {
                    if (index == null || index < 0 || index >= columns.size()) {
                        continue;
                    }
                    String choiceValue = normalizeQuestionHtml(normalizeMathSymbols(columns.get(index)));
                    choiceValue = stripChoiceLabelPrefix(choiceValue);
                    if (!choiceValue.isBlank()) {
                        choices.add(choiceValue);
                    }
                }

                String parsedAnswer = "";
                if (answerColumn >= 0 && answerColumn < columns.size()) {
                    parsedAnswer = normalizeQuestionHtml(normalizeMathSymbols(columns.get(answerColumn)));
                    parsedAnswer = normalizeCsvCorrectAnswer(parsedAnswer, choices);
                }

                // Open-ended when no choices are provided OR when Correct_Answer is blank.
                boolean openEnded = choices.isEmpty() || parsedAnswer.isBlank();

                if (openEnded && !questionText.startsWith("[TEXT_INPUT]")) {
                    questionText = "[TEXT_INPUT]" + questionText;
                }

                question.put("question", questionText);
                question.put("choices", openEnded ? new ArrayList<>() : choices);

                if (openEnded && parsedAnswer.isBlank()) {
                    parsedAnswer = "MANUAL_GRADE";
                }
                question.put("answer", parsedAnswer);

                if (difficultyColumn >= 0 && difficultyColumn < columns.size()) {
                    String normalizedDifficulty = normalizeDifficulty(columns.get(difficultyColumn));
                    if (!normalizedDifficulty.isBlank()) {
                        question.put("difficulty", normalizedDifficulty);
                    }
                }

                String itemDifficulty = question.get("difficulty") == null
                    ? "Medium"
                    : normalizeDifficulty(String.valueOf(question.get("difficulty")));
                applyDefaultItemParameters(question, itemDifficulty);

                questions.add(question);
            }
        }
        return questions;
    }

    private List<Map<String, Object>> parseQuestionsFromPdf(MultipartFile pdfFile) throws IOException {
        List<Map<String, Object>> questions = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfFile.getBytes())) {
            String text = new PDFTextStripper().getText(document);
            Matcher matcher = NUMBERED_QUESTION_PATTERN.matcher(text);
            while (matcher.find()) {
                String questionText = matcher.group(2) == null ? "" : matcher.group(2).trim();
                questionText = normalizeQuestionHtml(normalizeMathSymbols(questionText));
                if (questionText.isEmpty()) {
                    continue;
                }

                Map<String, Object> question = new HashMap<>();
                question.put("question", questionText);
                question.put("choices", new ArrayList<>());

                Matcher answerMatcher = INLINE_ANSWER_PATTERN.matcher(questionText);
                if (answerMatcher.find()) {
                    question.put("answer", normalizeMathSymbols(answerMatcher.group(1)));
                }

                applyDefaultItemParameters(question, "Medium");

                questions.add(question);
            }
        }
        return questions;
    }

    private Map<String, String> parseAnswerKey(List<Map<String, Object>> questions, MultipartFile answerKeyFile) throws IOException {
        Map<String, String> answerKey = new HashMap<>();

        for (int index = 0; index < questions.size(); index++) {
            Object value = questions.get(index).get("answer");
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                answerKey.put(String.valueOf(index + 1), normalizeMathSymbols(String.valueOf(value)));
            }
        }

        if (answerKeyFile == null || answerKeyFile.isEmpty()) {
            return answerKey;
        }

        String filename = answerKeyFile.getOriginalFilename() == null ? "" : answerKeyFile.getOriginalFilename().toLowerCase();
        if (filename.endsWith(".csv")) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(answerKeyFile.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    List<String> columns = splitCsvLine(line);
                    if (columns.size() >= 2 && columns.get(0).trim().matches("\\d+")) {
                        answerKey.put(columns.get(0).trim(), normalizeMathSymbols(columns.get(1)));
                    }
                }
            }
            return answerKey;
        }

        try (PDDocument document = Loader.loadPDF(answerKeyFile.getBytes())) {
            String text = new PDFTextStripper().getText(document);
            Matcher matcher = ANSWER_KEY_PATTERN.matcher(text);
            while (matcher.find()) {
                answerKey.put(matcher.group(1).trim(), normalizeMathSymbols(matcher.group(2)));
            }
        }

        return answerKey;
    }

    private Map<String, String> buildDifficultiesFromQuestions(List<Map<String, Object>> questions) {
        Map<String, String> difficulties = new HashMap<>();
        for (int index = 0; index < questions.size(); index++) {
            Map<String, Object> question = questions.get(index);
            String difficulty = "";
            if (question != null && question.get("difficulty") != null) {
                difficulty = normalizeDifficulty(String.valueOf(question.get("difficulty")));
            }
            difficulties.put(String.valueOf(index + 1), difficulty.isBlank() ? "Medium" : difficulty);
        }
        return difficulties;
    }

    private String normalizeDifficulty(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.startsWith("easy")) {
            return "Easy";
        }
        if (normalized.startsWith("hard")) {
            return "Hard";
        }
        if (normalized.startsWith("med")) {
            return "Medium";
        }
        return "";
    }

    private void applyDefaultItemParameters(Map<String, Object> question, String difficulty) {
        if (question == null) {
            return;
        }

        String normalizedDifficulty = normalizeDifficulty(difficulty);
        if (normalizedDifficulty.isBlank()) {
            normalizedDifficulty = "Medium";
        }

        double defaultA;
        double defaultB;
        double defaultC = 0.20;

        if ("Easy".equalsIgnoreCase(normalizedDifficulty)) {
            defaultA = 1.00;
            defaultB = -0.80;
        } else if ("Hard".equalsIgnoreCase(normalizedDifficulty)) {
            defaultA = 1.40;
            defaultB = 0.80;
        } else {
            defaultA = 1.20;
            defaultB = 0.00;
        }

        Double currentA = parseNumeric(question.get("irtA"));
        Double currentB = parseNumeric(question.get("irtB"));
        Double currentC = parseNumeric(question.get("irtC"));

        question.put("irtA", clamp(currentA == null ? defaultA : currentA, 0.30, 3.00));
        question.put("irtB", clamp(currentB == null ? defaultB : currentB, -3.00, 3.00));
        question.put("irtC", clamp(currentC == null ? defaultC : currentC, 0.00, 0.35));

        Object exposureRaw = question.get("exposureCount");
        if (!(exposureRaw instanceof Number) && parseNumeric(exposureRaw) == null) {
            question.put("exposureCount", 0);
        }
    }

    private Double parseNumeric(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String canonicalizeCsvHeader(String header) {
        if (header == null) {
            return "";
        }
        return normalize(header).replaceAll("[^a-z0-9]", "");
    }

    private int findCsvColumn(List<String> canonicalHeaders, String... aliases) {
        if (canonicalHeaders == null || canonicalHeaders.isEmpty() || aliases == null || aliases.length == 0) {
            return -1;
        }

        for (int index = 0; index < canonicalHeaders.size(); index++) {
            String header = canonicalHeaders.get(index);
            for (String alias : aliases) {
                if (alias != null && alias.equals(header)) {
                    return index;
                }
            }
        }

        return -1;
    }

    private String stripChoiceLabelPrefix(String choiceValue) {
        if (choiceValue == null || choiceValue.isBlank()) {
            return "";
        }

        return choiceValue
            .replaceFirst("(?i)^\\s*(?:\\(?[a-z0-9]{1,3}\\)?[.)-])\\s*", "")
            .trim();
    }

    private String normalizeCsvCorrectAnswer(String rawAnswer, List<String> choices) {
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return "";
        }

        String cleaned = normalizeQuestionHtml(rawAnswer);
        if (cleaned.isBlank()) {
            return "";
        }
        if ("MANUAL_GRADE".equalsIgnoreCase(cleaned)) {
            return "MANUAL_GRADE";
        }

        int choiceCount = choices == null ? 0 : choices.size();
        if (choiceCount <= 0) {
            return cleaned;
        }

        Integer index = parseCsvChoiceIndex(cleaned, choiceCount);
        if (index != null) {
            return generateChoiceLabel(index);
        }

        for (int i = 0; i < choiceCount; i++) {
            String choiceText = normalizeQuestionHtml(String.valueOf(choices.get(i)));
            if (!choiceText.isBlank() && normalize(choiceText).equals(normalize(cleaned))) {
                return generateChoiceLabel(i);
            }
        }

        return cleaned;
    }

    private Integer parseCsvChoiceIndex(String rawAnswer, int choiceCount) {
        if (rawAnswer == null || rawAnswer.isBlank() || choiceCount <= 0) {
            return null;
        }

        String candidate = rawAnswer.trim().toUpperCase();

        Matcher choiceTokenMatcher = Pattern.compile("^(?:CHOICE|OPTION)\\s*[_-]?\\s*([A-Z]{1,3}|\\d{1,4})$").matcher(candidate);
        if (choiceTokenMatcher.matches()) {
            String token = choiceTokenMatcher.group(1);
            int index;
            if (token != null && token.matches("\\d{1,4}")) {
                int parsed = parseIntSafe(token, 0);
                index = parsed - 1;
            } else {
                index = choiceLabelToZeroBasedIndex(token);
            }
            if (index >= 0 && index < choiceCount) {
                return index;
            }
        }

        Matcher numericMatcher = Pattern.compile("^\\(?\\s*(\\d{1,4})\\s*\\)?[.)-]?$").matcher(candidate);
        if (numericMatcher.matches()) {
            int parsed = parseIntSafe(numericMatcher.group(1), 0);
            int index = parsed - 1;
            if (index >= 0 && index < choiceCount) {
                return index;
            }
        }

        Matcher labelMatcher = Pattern.compile("^\\(?\\s*([A-Z]{1,3})\\s*\\)?[.)-]?$").matcher(candidate);
        if (labelMatcher.matches()) {
            int index = choiceLabelToZeroBasedIndex(labelMatcher.group(1));
            if (index >= 0 && index < choiceCount) {
                return index;
            }
        }

        return null;
    }

    private int choiceLabelToZeroBasedIndex(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return -1;
        }

        String upper = rawLabel.trim().toUpperCase();
        int value = 0;
        for (int index = 0; index < upper.length(); index++) {
            char ch = upper.charAt(index);
            if (ch < 'A' || ch > 'Z') {
                return -1;
            }
            value = (value * 26) + (ch - 'A' + 1);
        }

        return value - 1;
    }

    private String generateChoiceLabel(int zeroBasedIndex) {
        if (zeroBasedIndex < 0) {
            return "A";
        }

        int current = zeroBasedIndex;
        StringBuilder label = new StringBuilder();
        do {
            int remainder = current % 26;
            label.append((char) ('A' + remainder));
            current = (current / 26) - 1;
        } while (current >= 0);
        return label.reverse().toString();
    }

    private List<String> splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (inQuotes && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (character == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }

        result.add(current.toString());
        return result;
    }

    private void syncQuestionBankForPaper(OriginalProcessedPaper paper,
                                          List<Map<String, Object>> questions,
                                          Map<String, String> difficulties,
                                          Map<String, String> answerKey) {
        if (paper == null || questions == null || difficulties == null || answerKey == null) {
            return;
        }

        // Question-bank rows are now generated in-memory from processed papers.
        // Keep this method as a no-op to avoid growing the question_bank_items table.
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

            List<Map<String, Object>> questionRows = parseQuestionsJson(paper.getOriginalQuestionsJson());
            Map<String, String> difficultyMap = parseSimpleMapJson(paper.getDifficultiesJson());
            Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());

            for (int index = 0; index < questionRows.size(); index++) {
                Map<String, Object> row = questionRows.get(index);
                if (row == null) {
                    continue;
                }

                String key = String.valueOf(index + 1);
                String answerText = answerKeyMap.getOrDefault(key, "");
                String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));

                QuestionBankItem item = new QuestionBankItem();
                item.setSourceExamId(paper.getExamId());
                item.setSourceExamName(paper.getExamName());
                item.setSourceTeacherEmail(paper.getTeacherEmail());
                item.setSourceTeacherDepartment(paper.getDepartmentName());
                item.setSubject(paper.getSubject());
                item.setActivityType(paper.getActivityType());
                item.setQuestionOrder(index + 1);
                item.setQuestionText(resolveQuestionCandidate(row, difficulty, answerText, true));
                item.setChoicesJson(gson.toJson(extractChoices(row)));
                item.setAnswerText(answerText);
                item.setDifficulty(difficulty);
                item.setCreatedAt(paper.getProcessedAt() == null ? LocalDateTime.now() : paper.getProcessedAt());
                items.add(item);
            }
        }

        return items;
    }

    private long buildTemporaryQuestionBankItemId(QuestionBankItem item) {
        if (item == null) {
            return 0L;
        }

        Integer questionOrder = item.getQuestionOrder();

        String signature = normalize(item.getSourceExamId()) + "|"
            + (questionOrder == null ? "0" : questionOrder.toString()) + "|"
            + normalize(item.getSourceTeacherEmail()) + "|"
            + normalize(item.getSubject()) + "|"
            + normalize(item.getQuestionText());

        long hash = UUID.nameUUIDFromBytes(signature.getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
        if (hash == Long.MIN_VALUE) {
            return 0L;
        }
        return Math.abs(hash);
    }

    private List<String> extractChoices(Map<String, Object> row) {
        List<String> choices = new ArrayList<>();
        if (row == null) {
            return choices;
        }

        Object choicesObj = row.get("choices");
        if (choicesObj instanceof List<?> list) {
            for (Object value : list) {
                String normalizedChoice = normalizeQuestionHtml(value == null ? "" : String.valueOf(value));
                if (!normalizedChoice.isBlank()) {
                    choices.add(normalizedChoice);
                }
            }
            return choices;
        }

        if (choicesObj instanceof String text && !text.isBlank()) {
            for (String part : text.split("\\r?\\n|,")) {
                String normalizedChoice = normalizeQuestionHtml(part);
                if (!normalizedChoice.isBlank()) {
                    choices.add(normalizedChoice);
                }
            }
        }

        return choices;
    }

    private List<Map<String, Object>> buildQuestionSubset(List<Map<String, Object>> questions, List<Integer> selectedIndexes) {
        List<Map<String, Object>> subset = new ArrayList<>();
        if (questions == null || selectedIndexes == null) {
            return subset;
        }

        for (Integer selectedIndex : selectedIndexes) {
            if (selectedIndex == null || selectedIndex < 0 || selectedIndex >= questions.size()) {
                continue;
            }

            Map<String, Object> question = questions.get(selectedIndex);
            subset.add(question == null ? new LinkedHashMap<>() : new LinkedHashMap<>(question));
        }

        return subset;
    }

    private Map<String, String> buildSelectedMapSubset(List<Integer> selectedIndexes,
                                                       Map<String, String> source,
                                                       String defaultValue) {
        Map<String, String> subset = new LinkedHashMap<>();
        if (selectedIndexes == null) {
            return subset;
        }

        for (int index = 0; index < selectedIndexes.size(); index++) {
            Integer selectedIndex = selectedIndexes.get(index);
            if (selectedIndex == null || selectedIndex < 0) {
                continue;
            }

            String originalKey = String.valueOf(selectedIndex + 1);
            String newKey = String.valueOf(index + 1);
            subset.put(newKey, source.getOrDefault(originalKey, defaultValue));
        }

        return subset;
    }

    private List<Map<String, Object>> parseQuestionsJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> parsed = gson.fromJson(json,
                new TypeToken<List<Map<String, Object>>>() { }.getType());
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception exception) {
            return new ArrayList<>();
        }
    }

    private List<String> parseStringListJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> parsed = gson.fromJson(json,
                new TypeToken<List<String>>() { }.getType());
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception exception) {
            return new ArrayList<>();
        }
    }

    private Map<String, String> parseSimpleMapJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> parsed = gson.fromJson(json,
                new TypeToken<Map<String, String>>() { }.getType());
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception exception) {
            return new HashMap<>();
        }
    }

    private List<OriginalProcessedPaper> findTeacherProcessedPapers(String teacherEmail) {
        String rawTeacherEmail = teacherEmail == null ? "" : teacherEmail.trim();
        if (rawTeacherEmail.isBlank()) {
            return new ArrayList<>();
        }

        List<OriginalProcessedPaper> directMatches =
            originalProcessedPaperRepository.findByTeacherEmailOrderByProcessedAtDesc(rawTeacherEmail);
        if (!directMatches.isEmpty()) {
            return directMatches;
        }

        String normalizedTeacherEmail = normalize(rawTeacherEmail);
        return originalProcessedPaperRepository.findAll().stream()
            .filter(paper -> matchesTeacherOwner(normalizedTeacherEmail, paper.getTeacherEmail()))
            .sorted(Comparator.comparing(OriginalProcessedPaper::getProcessedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private boolean matchesTeacherOwner(String teacherEmail, String ownerEmail) {
        String normalizedTeacherEmail = normalize(teacherEmail);
        String normalizedOwnerEmail = normalize(ownerEmail);
        if (normalizedTeacherEmail.isBlank() || normalizedOwnerEmail.isBlank()) {
            return false;
        }

        if (normalizedTeacherEmail.equals(normalizedOwnerEmail)) {
            return true;
        }

        return normalizeOwnerKey(normalizedTeacherEmail).equals(normalizeOwnerKey(normalizedOwnerEmail));
    }

    private String normalizeOwnerKey(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        normalized = normalized.replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_|_$", "");
        return normalized;
    }

    private boolean isOwner(Principal principal, String ownerEmail) {
        String teacherEmail = principal != null ? principal.getName() : "";
        return matchesTeacherOwner(teacherEmail, ownerEmail);
    }

    private String resolveQuestionDisplay(Map<String, Object> questionRow, String difficulty, String answer) {
        return resolveQuestionCandidate(questionRow, difficulty, answer, true);
    }

    private String resolveQuestionCandidate(Map<String, Object> questionRow,
                                            String difficulty,
                                            String answer,
                                            boolean allowAnswerFallback) {
        String rawText = normalizeQuestionHtml(String.valueOf(questionRow.getOrDefault("question", "")));
        String cleaned = rawText.startsWith("[TEXT_INPUT]") ? rawText.substring("[TEXT_INPUT]".length()).trim() : rawText;
        List<String> candidates = new ArrayList<>();
        candidates.add(cleaned);

        Object questionTextField = questionRow.get("question_text");
        if (questionTextField != null) {
            candidates.add(normalizeQuestionHtml(String.valueOf(questionTextField)));
        }
        Object questionTextCamel = questionRow.get("questionText");
        if (questionTextCamel != null) {
            candidates.add(normalizeQuestionHtml(String.valueOf(questionTextCamel)));
        }
        Object textField = questionRow.get("text");
        if (textField != null) {
            candidates.add(normalizeQuestionHtml(String.valueOf(textField)));
        }

        Object choicesObj = questionRow.get("choices");
        if (choicesObj instanceof List<?> choices) {
            for (Object choice : choices) {
                candidates.add(normalizeQuestionHtml(String.valueOf(choice)));
            }
        } else if (choicesObj instanceof String choicesText) {
            for (String part : choicesText.split("\\r?\\n|,")) {
                candidates.add(normalizeQuestionHtml(part));
            }
        }

        for (Map.Entry<String, Object> entry : questionRow.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase();
            if (List.of("question", "choices", "answer", "difficulty", "id", "type", "questiontype")
                .contains(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value != null) {
                candidates.add(normalizeQuestionHtml(String.valueOf(value)));
            }
        }

        String bestCandidate = "";
        for (String candidate : candidates) {
            if (!looksLikePlaceholderQuestion(candidate, difficulty, answer) && candidate.length() > bestCandidate.length()) {
                bestCandidate = candidate;
            }
        }

        if (!bestCandidate.isBlank()) {
            return normalizeQuestionHtml(bestCandidate);
        }

        if (allowAnswerFallback && answer != null) {
            String answerText = answer.trim();
            if (answerText.length() > 12 && !answerText.equalsIgnoreCase("MANUAL_GRADE")) {
                return answerText;
            }
        }

        return "";
    }

    private String normalizeQuestionHtml(String raw) {
        if (raw == null) {
            return "";
        }

        String value = raw.trim();
        if (value.isBlank()) {
            return "";
        }

        value = stripClipboardFragmentMarkers(value);

        for (int pass = 0; pass < 4; pass++) {
            boolean hasRawTags = HTML_TAG_PATTERN.matcher(value).find();
            boolean hasEscapedTags = ESCAPED_HTML_TAG_PATTERN.matcher(value).find()
                || value.contains("&lt;!--")
                || value.contains("&quot;")
                || value.contains("&amp;lt;")
                || value.contains("&amp;quot;")
                || value.contains("&#");
            if (!hasEscapedTags) {
                break;
            }

            String decoded = HtmlUtils.htmlUnescape(value);
            if (decoded == null || decoded.equals(value)) {
                break;
            }

            value = decoded;
            value = stripClipboardFragmentMarkers(value);

            if (hasRawTags && !ESCAPED_HTML_TAG_PATTERN.matcher(value).find() && !value.contains("&amp;lt;")) {
                break;
            }
        }

        value = convertSupSubHtmlToTokens(value);
        return normalizeEquationArtifacts(value.trim());
    }

    private String convertSupSubHtmlToTokens(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value;
        for (int pass = 0; pass < 4; pass++) {
            String updated = normalized
                .replaceAll("(?i)<sup[^>]*>\\s*(.*?)\\s*</sup>", "^$1")
                .replaceAll("(?i)<sub[^>]*>\\s*(.*?)\\s*</sub>", "_$1");
            if (updated.equals(normalized)) {
                break;
            }
            normalized = updated;
        }

        return normalized;
    }

    private boolean normalizeQuestionRowsInPlace(List<Map<String, Object>> questionRows) {
        if (questionRows == null || questionRows.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (Map<String, Object> row : questionRows) {
            if (row == null || row.isEmpty()) {
                continue;
            }

            changed |= normalizeQuestionField(row, "question");
            changed |= normalizeQuestionField(row, "question_text");
            changed |= normalizeQuestionField(row, "questionText");
            changed |= normalizeQuestionField(row, "text");

            Object choicesObj = row.get("choices");
            if (choicesObj instanceof List<?> list) {
                List<String> normalizedChoices = new ArrayList<>();
                boolean listChanged = false;
                for (Object item : list) {
                    String rawChoice = item == null ? "" : String.valueOf(item);
                    String normalizedChoice = normalizeQuestionHtml(rawChoice);
                    normalizedChoices.add(normalizedChoice);
                    if (!rawChoice.equals(normalizedChoice)) {
                        listChanged = true;
                    }
                }
                if (listChanged) {
                    row.put("choices", normalizedChoices);
                    changed = true;
                }
            } else if (choicesObj instanceof String choicesText) {
                String normalizedChoicesText = normalizeQuestionHtml(choicesText);
                if (!choicesText.equals(normalizedChoicesText)) {
                    row.put("choices", normalizedChoicesText);
                    changed = true;
                }
            }
        }

        return changed;
    }

    private boolean normalizeQuestionField(Map<String, Object> row, String key) {
        Object rawObj = row.get(key);
        if (rawObj == null) {
            return false;
        }

        String raw = String.valueOf(rawObj);
        String normalized = normalizeQuestionHtml(raw);
        if (raw.equals(normalized)) {
            return false;
        }

        row.put(key, normalized);
        return true;
    }

    private String stripClipboardFragmentMarkers(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
            .replaceAll("(?i)<!--\\s*(StartFragment|EndFragment)\\s*-->", "")
            .replaceAll("(?i)&lt;!--\\s*(StartFragment|EndFragment)\\s*--&gt;", "")
            .trim();
    }

    private String toPlainQuestionText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = normalizeQuestionHtml(value);
        if (normalized.isBlank()) {
            return "";
        }

        String withBreaks = normalized
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</p\\s*>", "\n")
            .replaceAll("(?i)</div\\s*>", "\n")
            .replaceAll("(?i)</li\\s*>", "\n");

        String noTags = withBreaks.replaceAll("(?is)<[^>]+>", "");
        String decoded = HtmlUtils.htmlUnescape(noTags)
            .replace('\u00A0', ' ');

        return decoded
            .replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll(" *\\n *", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private boolean looksLikePlaceholderQuestion(String value, String difficulty, String answer) {
        if (value == null) {
            return true;
        }
        String text = value.trim();
        if (text.isBlank()) {
            return true;
        }

        String normalized = text.toLowerCase();
        if (normalized.matches("\\d+")) {
            return true;
        }

        if (List.of("id", " no", "number", "question", "questions", "answer", "answers", "difficulty")
                .contains(normalized)) {
                return true;
            }

        if (List.of("type", "mc", "open", "multiple choice", "open ended", "question_text", "correct_answer")
            .contains(normalized)) {
            return true;
        }

        if (List.of("easy", "medium", "hard").contains(normalized)) {
            return true;
        }

        if (difficulty != null && normalized.equals(difficulty.trim().toLowerCase())) {
            return true;
        }

        if (answer != null && !answer.isBlank() && normalized.equals(answer.trim().toLowerCase())) {
            return true;
        }

        return false;
    }

    private String pickBestQuestionCandidate(List<String> columns, int questionColumn, int answerColumn) {
        String best = "";
        for (int index = 0; index < columns.size(); index++) {
            if (index == answerColumn) {
                continue;
            }

            String candidate = columns.get(index) == null ? "" : columns.get(index).trim();
            if (index == questionColumn && !looksLikePlaceholderQuestion(candidate, null, null)) {
                return candidate;
            }

            if (!looksLikePlaceholderQuestion(candidate, null, null) && candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
    }

    private String buildReturnToQuery(String returnTo) {
        if (returnTo == null || returnTo.isBlank()) {
            return "";
        }
        return "?returnTo=" + java.net.URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    private Map<String, String> reindexMap(Map<String, String> source, int maxCount) {
        Map<String, String> target = new HashMap<>();
        for (int index = 1; index <= maxCount; index++) {
            String value = source.get(String.valueOf(index));
            if (value != null) {
                target.put(String.valueOf(index), value);
            }
        }
        return target;
    }

    private List<ExportQuestionRow> buildExportQuestionRows(OriginalProcessedPaper paper) {
        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> difficultiesMap = parseSimpleMapJson(paper.getDifficultiesJson());
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());

        List<ExportQuestionRow> rows = new ArrayList<>();
        for (int index = 0; index < questions.size(); index++) {
            String key = String.valueOf(index + 1);
            Map<String, Object> row = questions.get(index) == null ? new HashMap<>() : questions.get(index);

            String difficulty = normalizeDifficulty(difficultiesMap.getOrDefault(key, "Medium"));
            if (difficulty.isBlank()) {
                difficulty = "Medium";
            }

            String rawAnswer = answerKeyMap.getOrDefault(
                key,
                row.get("answer") == null ? "" : String.valueOf(row.get("answer")));

            String questionText = resolveQuestionDisplay(row, difficulty, rawAnswer);
            questionText = toPlainQuestionText(questionText);
            if (questionText.isBlank()) {
                questionText = "Question " + (index + 1);
            }

            List<String> choices = new ArrayList<>();
            for (String choice : extractChoices(row)) {
                String plainChoice = toPlainQuestionText(choice);
                if (!plainChoice.isBlank()) {
                    choices.add(plainChoice);
                }
            }

            String answer = formatAnswerForExport(rawAnswer, choices);
            rows.add(new ExportQuestionRow(index + 1, difficulty, questionText, choices, answer));
        }

        return rows;
    }

    private ResponseEntity<byte[]> buildCsvExportResponse(OriginalProcessedPaper paper,
                                                          List<ExportQuestionRow> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append("No,Difficulty,Question,Choices,Correct_Answer\n");

        for (ExportQuestionRow row : rows) {
            csv.append(csvCell(String.valueOf(row.number()))).append(',')
                .append(csvCell(row.difficulty())).append(',')
                .append(csvCell(row.question())).append(',')
                .append(csvCell(formatChoicesForExport(row.choices()))).append(',')
                .append(csvCell(row.answer()))
                .append('\n');
        }

        String filename = sanitizeFilename(paper.getExamName()) + "-with-answers.csv";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<byte[]> buildPdfExportResponse(OriginalProcessedPaper paper,
                                                          List<ExportQuestionRow> rows) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(document, outputStream);
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        Font bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

        document.add(new Paragraph(
            (paper.getExamName() == null || paper.getExamName().isBlank()) ? "Exam" : paper.getExamName(),
            titleFont));
        document.add(new Paragraph("Subject: " + safeText(paper.getSubject()), bodyFont));
        document.add(new Paragraph("Type: " + safeText(paper.getActivityType()), bodyFont));
        document.add(new Paragraph("Total Questions: " + rows.size(), bodyFont));
        document.add(Chunk.NEWLINE);

        for (ExportQuestionRow row : rows) {
            document.add(new Paragraph(
                row.number() + ". [" + row.difficulty() + "] " + row.question(),
                sectionFont));

            for (int index = 0; index < row.choices().size(); index++) {
                String label = generateChoiceLabel(index);
                document.add(new Paragraph("    " + label + ". " + row.choices().get(index), bodyFont));
            }

            document.add(new Paragraph("Answer: " + row.answer(), bodyFont));
            document.add(Chunk.NEWLINE);
        }

        document.close();

        String filename = sanitizeFilename(paper.getExamName()) + "-with-answers.pdf";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_PDF)
            .body(outputStream.toByteArray());
    }

    private ResponseEntity<byte[]> buildDocxExportResponse(OriginalProcessedPaper paper,
                                                           List<ExportQuestionRow> rows) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph title = document.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(16);
            titleRun.setText((paper.getExamName() == null || paper.getExamName().isBlank()) ? "Exam" : paper.getExamName());

            XWPFParagraph meta = document.createParagraph();
            XWPFRun metaRun = meta.createRun();
            metaRun.setText("Subject: " + safeText(paper.getSubject())
                + " | Type: " + safeText(paper.getActivityType())
                + " | Total Questions: " + rows.size());

            for (ExportQuestionRow row : rows) {
                XWPFParagraph questionParagraph = document.createParagraph();
                XWPFRun questionRun = questionParagraph.createRun();
                questionRun.setBold(true);
                questionRun.setText(row.number() + ". [" + row.difficulty() + "] " + row.question());

                for (int index = 0; index < row.choices().size(); index++) {
                    XWPFParagraph choiceParagraph = document.createParagraph();
                    XWPFRun choiceRun = choiceParagraph.createRun();
                    choiceRun.setText(generateChoiceLabel(index) + ". " + row.choices().get(index));
                }

                XWPFParagraph answerParagraph = document.createParagraph();
                XWPFRun answerRun = answerParagraph.createRun();
                answerRun.setItalic(true);
                answerRun.setText("Answer: " + row.answer());

                document.createParagraph();
            }

            document.write(outputStream);
        }

        String filename = sanitizeFilename(paper.getExamName()) + "-with-answers.docx";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            .body(outputStream.toByteArray());
    }

    private String formatAnswerForExport(String rawAnswer, List<String> choices) {
        String normalized = normalizeQuestionHtml(rawAnswer);
        if (normalized.isBlank()) {
            return "Not Set";
        }

        if ("MANUAL_GRADE".equalsIgnoreCase(normalized)) {
            return "Manual grading required";
        }

        if (choices != null && !choices.isEmpty()) {
            Integer answerIndex = parseCsvChoiceIndex(normalized, choices.size());
            if (answerIndex != null && answerIndex >= 0 && answerIndex < choices.size()) {
                return generateChoiceLabel(answerIndex) + ". " + choices.get(answerIndex);
            }
        }

        return toPlainQuestionText(normalized);
    }

    private String formatChoicesForExport(List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return "";
        }

        List<String> labeledChoices = new ArrayList<>();
        for (int index = 0; index < choices.size(); index++) {
            labeledChoices.add(generateChoiceLabel(index) + ". " + choices.get(index));
        }

        return String.join(" | ", labeledChoices);
    }

    private String csvCell(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private String sanitizeFilename(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "exam";
        }

        String sanitized = rawName.replaceAll("[^a-zA-Z0-9._-]", "_");
        sanitized = sanitized.replaceAll("_+", "_");
        sanitized = sanitized.replaceAll("^[_\\.]+|[_\\.]+$", "");
        return sanitized.isBlank() ? "exam" : sanitized;
    }

    private String safeText(String value) {
        return (value == null || value.isBlank()) ? "N/A" : value;
    }

    private record ExportQuestionRow(int number,
                                     String difficulty,
                                     String question,
                                     List<String> choices,
                                     String answer) {
    }

    private String normalizeMathSymbols(String text) {
        if (text == null) {
            return "";
        }

        String normalized = HtmlUtils.htmlUnescape(text);
        if (normalized == null) {
            normalized = text;
        }

        Map<String, String> entities = Map.ofEntries(
            Map.entry("&plusmn;", "±"),
            Map.entry("&times;", "×"),
            Map.entry("&divide;", "÷"),
            Map.entry("&div;", "÷"),
            Map.entry("&le;", "≤"),
            Map.entry("&ge;", "≥"),
            Map.entry("&ne;", "≠"),
            Map.entry("&asymp;", "≈"),
            Map.entry("&equiv;", "≡"),
            Map.entry("&sum;", "∑"),
            Map.entry("&prod;", "∏"),
            Map.entry("&radic;", "√"),
            Map.entry("&infin;", "∞"),
            Map.entry("&pi;", "π"),
            Map.entry("&alpha;", "α"),
            Map.entry("&beta;", "β"),
            Map.entry("&gamma;", "γ"),
            Map.entry("&Delta;", "Δ"),
            Map.entry("&theta;", "θ"),
            Map.entry("&int;", "∫"),
            Map.entry("&there4;", "∴"),
            Map.entry("&because;", "∵"),
            Map.entry("&perp;", "⊥"),
            Map.entry("&parallel;", "∥")
        );

        for (Map.Entry<String, String> entry : entities.entrySet()) {
            normalized = normalized.replace(entry.getKey(), entry.getValue());
        }

        normalized = decodeNumericHtmlEntitiesSafely(normalized, Pattern.compile("&#(\\d+);"), 10);
        normalized = decodeNumericHtmlEntitiesSafely(normalized, Pattern.compile("&#x([0-9a-fA-F]+);"), 16);

        return normalizeEquationArtifacts(normalized.trim());
    }

    private String decodeNumericHtmlEntitiesSafely(String input, Pattern pattern, int radix) {
        if (input == null || input.isBlank()) {
            return "";
        }

        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(0);
            try {
                int codePoint = Integer.parseInt(matcher.group(1), radix);
                if (Character.isValidCodePoint(codePoint)
                    && !(codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                    replacement = new String(Character.toChars(codePoint));
                }
            } catch (Exception ignored) {
                // Keep original entity text when parsing fails.
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private String normalizeEquationArtifacts(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String normalized = text
            .replace('\u00A0', ' ')
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .replace('\u2044', '/')
            .replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll(" *\\n *", "\\n")
            .replaceAll("\\n{3,}", "\\n\\n");

        normalized = normalized
            .replace("\\times", "×")
            .replace("\\div", "÷")
            .replace("\\le", "≤")
            .replace("\\ge", "≥")
            .replace("\\ne", "≠")
            .replace("\\approx", "≈")
            .replace("\\infty", "∞")
            .replace("\\pi", "π")
            .replace("\\alpha", "α")
            .replace("\\beta", "β")
            .replace("\\gamma", "γ")
            .replace("\\Delta", "Δ")
            .replace("\\theta", "θ")
            .replace("\\sum", "∑")
            .replace("\\prod", "∏")
            .replace("\\sqrt", "√")
            .replace("\\int", "∫");

        return decodeSuperscriptAndSubscriptTokens(normalized).trim();
    }

    private String decodeSuperscriptAndSubscriptTokens(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        StringBuilder rebuilt = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            if (value.startsWith("^\\circ", index)) {
                rebuilt.append('\u00B0');
                index += "^\\circ".length() - 1;
                continue;
            }

            char current = value.charAt(index);
            if (current == '^' && index + 1 < value.length()) {
                Character superscript = toSuperscriptChar(value.charAt(index + 1));
                if (superscript != null) {
                    rebuilt.append(superscript);
                    index++;
                    continue;
                }
            }

            if (current == '_' && index + 1 < value.length()) {
                Character subscript = toSubscriptChar(value.charAt(index + 1));
                if (subscript != null) {
                    rebuilt.append(subscript);
                    index++;
                    continue;
                }
            }

            rebuilt.append(current);
        }

        return rebuilt.toString();
    }

    private Character toSuperscriptChar(char value) {
        return switch (value) {
            case '0' -> '\u2070';
            case '1' -> '\u00B9';
            case '2' -> '\u00B2';
            case '3' -> '\u00B3';
            case '4' -> '\u2074';
            case '5' -> '\u2075';
            case '6' -> '\u2076';
            case '7' -> '\u2077';
            case '8' -> '\u2078';
            case '9' -> '\u2079';
            case '+' -> '\u207A';
            case '-' -> '\u207B';
            case '=' -> '\u207C';
            case '(' -> '\u207D';
            case ')' -> '\u207E';
            case 'n', 'N' -> '\u207F';
            default -> null;
        };
    }

    private Character toSubscriptChar(char value) {
        return switch (value) {
            case '0' -> '\u2080';
            case '1' -> '\u2081';
            case '2' -> '\u2082';
            case '3' -> '\u2083';
            case '4' -> '\u2084';
            case '5' -> '\u2085';
            case '6' -> '\u2086';
            case '7' -> '\u2087';
            case '8' -> '\u2088';
            case '9' -> '\u2089';
            case '+' -> '\u208A';
            case '-' -> '\u208B';
            case '=' -> '\u208C';
            case '(' -> '\u208D';
            case ')' -> '\u208E';
            default -> null;
        };
    }
}
