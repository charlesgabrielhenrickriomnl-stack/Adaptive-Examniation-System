package com.exam.Controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.exam.entity.DistributedExam;
import com.exam.entity.EnrolledStudent;
import com.exam.entity.ExamSubmission;
import com.exam.entity.OriginalProcessedPaper;
import com.exam.entity.Subject;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.ExamSubmissionRepository;
import com.exam.repository.OriginalProcessedPaperRepository;
import com.exam.repository.SubjectRepository;
import com.exam.service.FisherYatesService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Controller
@RequestMapping("/teacher")
public class TeacherController {
    @Autowired
    private com.exam.repository.DistributedExamRepository distributedExamRepository;

    private static final DateTimeFormatter DEADLINE_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");

    private static final Pattern NUMBERED_QUESTION_PATTERN =
        Pattern.compile("(?m)^\\s*(\\d+)\\s*[\\).:-]\\s*(.+)$");

    private static final Pattern INLINE_ANSWER_PATTERN =
        Pattern.compile("(?i)\\banswer\\s*[:\\-]\\s*([A-D]|[^\\r\\n]+)");

    private static final Pattern ANSWER_KEY_PATTERN =
        Pattern.compile("(?m)^\\s*(\\d+)\\s*[\\).:-]?\\s*([A-D]|[^\\r\\n]+)\\s*$");

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
    private FisherYatesService fisherYatesService;

    @GetMapping("/homepage")
    public String homepage(Model model, Principal principal) {
        String teacherEmail = principal != null ? principal.getName() : "";
        List<Subject> subjects = teacherEmail.isBlank()
            ? new ArrayList<>()
            : subjectRepository.findByTeacherEmail(teacherEmail);
        model.addAttribute("subjects", subjects);
        model.addAttribute("teacherEmail", teacherEmail);
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

    @GetMapping("/students")
    public String studentsAlias() {
        return "redirect:/teacher/subjects";
    }

    @GetMapping("/processed-papers")
    public String processedPapers(@RequestParam(required = false) String search, Model model, Principal principal) {
        String teacherEmail = principal != null ? principal.getName() : "";
        List<OriginalProcessedPaper> papers = teacherEmail.isBlank()
            ? new ArrayList<>()
            : originalProcessedPaperRepository.findByTeacherEmailOrderByProcessedAtDesc(teacherEmail);

        String normalizedSearch = normalize(search);
        List<Map<String, Object>> processedExams = new ArrayList<>();

        for (OriginalProcessedPaper paper : papers) {
            List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
            String combinedText = normalize(paper.getExamName()) + " " + normalize(paper.getSubject()) + " "
                + normalize(paper.getOriginalQuestionsJson()) + " " + normalize(paper.getAnswerKeyJson());

            if (!normalizedSearch.isEmpty() && !combinedText.contains(normalizedSearch)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("examId", paper.getExamId());
            row.put("examName", paper.getExamName());
            row.put("subject", paper.getSubject());
            row.put("activityType", paper.getActivityType());
            row.put("uploadedAt", paper.getProcessedAt());
            row.put("questions", questions);
            processedExams.add(row);
        }

        model.addAttribute("search", search == null ? "" : search);
        model.addAttribute("processedExams", processedExams);
        model.addAttribute("totalProcessed", processedExams.size());
        return "teacher-processed-papers";
    }

    @PostMapping("/process-exams-upload")
    public String processExamsUpload(@RequestParam("examCreated") MultipartFile examCreated,
                                     @RequestParam(value = "answerKeyPdf", required = false) MultipartFile answerKeyPdf,
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
            Map<String, String> answerKeyMap = parseAnswerKey(questions, answerKeyPdf);
            Map<String, String> difficulties = buildDifficultiesFromQuestions(questions);

            String originalFilename = examCreated.getOriginalFilename();
            String generatedExamName = deriveExamName(quizName, originalFilename);
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

            originalProcessedPaperRepository.save(paper);
            redirectAttributes.addFlashAttribute("successMessage", "Exam processed successfully.");
            return "redirect:/teacher/processed-papers";
        } catch (Exception exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to process exam: " + exception.getMessage());
            return "redirect:/teacher/homepage";
        }
    }

    @GetMapping("/processed-papers/{examId}")
    public String processedPaperDetail(@PathVariable String examId,
                                       @RequestParam(required = false) String questionSearch,
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

        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());
        Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());
        String normalizedSearch = normalize(questionSearch);

        List<Map<String, Object>> questionRows = new ArrayList<>();
        int displayNumber = 1;
        for (int index = 0; index < questions.size(); index++) {
            Map<String, Object> question = questions.get(index);
            String number = String.valueOf(index + 1);
            String answerText = answerKey.getOrDefault(number, "Not Set");
            String difficulty = difficulties.getOrDefault(number, "Medium");
            String questionText = resolveQuestionDisplay(question, difficulty, answerText);

            if (questionText == null || questionText.isBlank()) {
                continue;
            }

            String searchable = normalize(questionText) + " " + normalize(answerText);
            if (!normalizedSearch.isEmpty() && !searchable.contains(normalizedSearch)) {
                continue;
            }

            Map<String, Object> row = new HashMap<>();
            row.put("number", displayNumber++);
            row.put("question", questionText);
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

        model.addAttribute("exam", exam);
        model.addAttribute("questionRows", questionRows);
        model.addAttribute("questionSearch", questionSearch == null ? "" : questionSearch);
        return "teacher-processed-paper-detail";
    }

    @PostMapping("/processed-papers/{examId}/delete")
    public String deleteProcessedPaper(@PathVariable String examId,
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
    public String repairProcessedPaperQuestions(@PathVariable String examId,
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
            redirectAttributes.addFlashAttribute("successMessage", repaired + " question row(s) repaired.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "No malformed question rows found.");
        }

        return "redirect:/teacher/processed-papers/" + examId;
    }

    @GetMapping("/manage-questions/{examId}")
    public String manageQuestions(@PathVariable String examId,
                                  @RequestParam(required = false) String returnTo,
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

        Map<String, Object> exam = new HashMap<>();
        exam.put("examId", paper.getExamId());
        exam.put("examName", paper.getExamName());
        exam.put("subject", paper.getSubject());
        exam.put("activityType", paper.getActivityType());
        exam.put("questions", questions);
        exam.put("difficulties", difficulties);
        exam.put("answerKey", answerKey);

        model.addAttribute("exam", exam);
        model.addAttribute("questionDisplay", questions);
        model.addAttribute("returnTo", returnTo == null || returnTo.isBlank() ? "/teacher/processed-papers" : returnTo);
        return "manage-questions";
    }

    @PostMapping("/add-question")
    public String addQuestion(@RequestParam String examId,
                              @RequestParam String questionText,
                              @RequestParam(required = false) String questionType,
                              @RequestParam(required = false) String choicesText,
                              @RequestParam(required = false) String answer,
                              @RequestParam String difficulty,
                              @RequestParam(required = false) String returnTo,
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

        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());
        Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());

        Map<String, Object> newQuestion = new HashMap<>();
        String normalizedQuestion = questionText == null ? "" : questionText.trim();
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

        int newNumber = questions.size() + 1;
        String key = String.valueOf(newNumber);
        questions.add(newQuestion);
        difficulties.put(key, normalizeMathSymbols(difficulty).isBlank() ? "Medium" : normalizeMathSymbols(difficulty));
        answerKey.put(key, openEnded ? "MANUAL_GRADE" : normalizeMathSymbols(answer));

        paper.setOriginalQuestionsJson(gson.toJson(questions));
        paper.setDifficultiesJson(gson.toJson(difficulties));
        paper.setAnswerKeyJson(gson.toJson(answerKey));
        originalProcessedPaperRepository.save(paper);

        redirectAttributes.addFlashAttribute("successMessage", "Question added successfully.");
        return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
    }

    @PostMapping("/edit-question")
    public String editQuestion(@RequestParam String examId,
                               @RequestParam Integer questionIndex,
                               @RequestParam String questionText,
                               @RequestParam(required = false) String questionType,
                               @RequestParam(required = false) String answer,
                               @RequestParam String difficulty,
                               @RequestParam(required = false) String returnTo,
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

        List<Map<String, Object>> questions = parseQuestionsJson(paper.getOriginalQuestionsJson());
        if (questionIndex == null || questionIndex < 0 || questionIndex >= questions.size()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Invalid question index.");
            return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
        }

        Map<String, String> difficulties = parseSimpleMapJson(paper.getDifficultiesJson());
        Map<String, String> answerKey = parseSimpleMapJson(paper.getAnswerKeyJson());

        boolean openEnded = "OPEN_ENDED".equalsIgnoreCase(questionType);
        String normalizedQuestion = questionText == null ? "" : questionText.trim();
        if (openEnded && !normalizedQuestion.startsWith("[TEXT_INPUT]")) {
            normalizedQuestion = "[TEXT_INPUT]" + normalizedQuestion;
        }

        Map<String, Object> target = questions.get(questionIndex);
        target.put("question", normalizedQuestion);

        String key = String.valueOf(questionIndex + 1);
        difficulties.put(key, normalizeMathSymbols(difficulty).isBlank() ? "Medium" : normalizeMathSymbols(difficulty));
        answerKey.put(key, openEnded ? "MANUAL_GRADE" : normalizeMathSymbols(answer));

        paper.setOriginalQuestionsJson(gson.toJson(questions));
        paper.setDifficultiesJson(gson.toJson(difficulties));
        paper.setAnswerKeyJson(gson.toJson(answerKey));
        originalProcessedPaperRepository.save(paper);

        redirectAttributes.addFlashAttribute("successMessage", "Question updated successfully.");
        return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
    }

    @PostMapping("/delete-question")
    public String deleteQuestion(@RequestParam String examId,
                                 @RequestParam Integer questionIndex,
                                 @RequestParam(required = false) String returnTo,
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

        redirectAttributes.addFlashAttribute("successMessage", "Question deleted successfully.");
        return "redirect:/teacher/manage-questions/" + examId + buildReturnToQuery(returnTo);
    }

    @GetMapping("/subject-classroom/{id}")
    public String subjectClassroom(@PathVariable Long id, Model model, Principal principal) {
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
        List<OriginalProcessedPaper> teacherPapers = teacherEmail.isBlank()
            ? new ArrayList<>()
            : originalProcessedPaperRepository.findByTeacherEmailOrderByProcessedAtDesc(teacherEmail);

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

        List<DistributedExam> distributedExams = distributedExamRepository.findAll().stream()
            .filter(item -> item.getSubject() != null && item.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .toList();

        List<Map<String, Object>> quizDistributionSummary = buildQuizDistributionSummary(distributedExams);
        int distributedSubmittedCount = quizDistributionSummary.stream()
            .mapToInt(item -> (int) item.getOrDefault("submittedCount", 0))
            .sum();
        int distributedNotSubmittedCount = quizDistributionSummary.stream()
            .mapToInt(item -> (int) item.getOrDefault("notSubmittedCount", 0))
            .sum();

        model.addAttribute("distributionTracker", quizDistributionSummary);
        model.addAttribute("quizDistributionSummary", quizDistributionSummary);
        model.addAttribute("classroomStudentSummary", new ArrayList<>());
        model.addAttribute("distributedSubmittedCount", distributedSubmittedCount);
        model.addAttribute("distributedNotSubmittedCount", distributedNotSubmittedCount);
        return "subject-classroom";
    }

    @GetMapping("/subject-classroom/{id}/distributed-exams")
    public String subjectDistributedExams(@PathVariable Long id, Model model, Principal principal) {
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

        List<Map<String, Object>> quizDistributionSummary = buildQuizDistributionSummary(distributedExams);

        model.addAttribute("subject", subject);
        model.addAttribute("quizDistributionSummary", quizDistributionSummary);
        model.addAttribute("distributedExamCount", quizDistributionSummary.size());
        return "subject-distributed-exams";
    }

    @PostMapping("/subject-classroom/{id}/distributed-exams/delete")
    public String deleteDistributedExamBatch(@PathVariable Long id,
                                             @RequestParam String examName,
                                             @RequestParam String activityType,
                                             @RequestParam Integer timeLimit,
                                             @RequestParam(required = false) String deadline,
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

        String normalizedDeadline = deadline == null ? "" : deadline.trim();

        List<DistributedExam> matchingDistributions = distributedExamRepository.findAll().stream()
            .filter(item -> item.getSubject() != null && item.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .filter(item -> examName != null && examName.equals(item.getExamName()))
            .filter(item -> activityType != null && activityType.equals(item.getActivityType()))
            .filter(item -> timeLimit != null && timeLimit.equals(item.getTimeLimit()))
            .filter(item -> {
                String itemDeadline = item.getDeadline() == null ? "" : item.getDeadline().trim();
                return itemDeadline.equals(normalizedDeadline);
            })
            .toList();

        List<ExamSubmission> matchingSubmissions = examSubmissionRepository.findAll().stream()
            .filter(sub -> sub.getSubject() != null && sub.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .filter(sub -> examName != null && examName.equals(sub.getExamName()))
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
    public String distributeSelected(@RequestParam Long subjectId,
                                     @RequestParam String examId,
                                     @RequestParam Integer questionCount,
                                     @RequestParam Integer timeLimit,
                                     @RequestParam Integer easyPercent,
                                     @RequestParam Integer mediumPercent,
                                     @RequestParam Integer hardPercent,
                                     @RequestParam(required = false) String deadline,
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

        if (selectedStudents == null || selectedStudents.isEmpty()) {
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

        // Exam distribution: Only mark exam as available for students, do not create ExamSubmission here.
        // TODO: Implement a proper distribution record if needed (e.g., a DistributedExam entity/table).
        int distributed = 0;
        for (String studentEmail : selectedStudents) {
            if (studentEmail == null || studentEmail.isBlank()) {
                continue;
            }
            DistributedExam distExam = new DistributedExam();
            distExam.setStudentEmail(studentEmail.trim());
            distExam.setExamId(paper.getExamId());
            distExam.setSubject(subject.getSubjectName());
            distExam.setExamName(paper.getExamName());
            distExam.setActivityType(paper.getActivityType());
            distExam.setTimeLimit((timeLimit != null && timeLimit > 0) ? timeLimit : 60);
            distExam.setDeadline(deadline == null ? "" : deadline);
            distExam.setDistributedAt(java.time.LocalDateTime.now());
            distExam.setSubmitted(false);
            distributedExamRepository.save(distExam);
            distributed++;
        }

        if (distributed == 0) {
            redirectAttributes.addFlashAttribute("errorMessage", "No valid students selected for distribution.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage", "Quiz distributed to " + distributed + " student(s).");
        }
        return "redirect:/teacher/subject-classroom/" + subjectId;
    }

    @GetMapping("/subject-classroom/{id}/enrolled-students")
    public String subjectEnrolledStudents(@PathVariable Long id, Model model, Principal principal) {
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
        model.addAttribute("subject", subject);
        model.addAttribute("enrolledStudents", enrolledStudents);
        model.addAttribute("enrolledCount", enrolledStudents.size());
        return "subject-enrolled-students";
    }

    @GetMapping("/subject-classroom/{id}/distribution-students")
    public String subjectDistributionStudents(@PathVariable Long id,
                                             @RequestParam(required = false) String filterExamName,
                                             @RequestParam(required = false) String filterActivityType,
                                             @RequestParam(required = false) Integer filterTimeLimit,
                                             @RequestParam(required = false) String filterDeadline,
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

        // Fetch enrolled students
        List<EnrolledStudent> enrolledStudents = enrolledStudentRepository.findBySubjectId(subject.getId());

        // Fetch submissions for this subject and quiz
        List<ExamSubmission> submissions = examSubmissionRepository.findAll().stream()
            .filter(sub -> sub.getSubject() != null && sub.getSubject().equalsIgnoreCase(subject.getSubjectName()))
            .filter(sub -> filterExamName == null || sub.getExamName().equalsIgnoreCase(filterExamName))
            .filter(sub -> filterActivityType == null || (sub.getActivityType() != null && sub.getActivityType().equalsIgnoreCase(filterActivityType)))
            .filter(sub -> filterTimeLimit == null || (sub.getTimeLimit() != null && sub.getTimeLimit().equals(filterTimeLimit)))
            .filter(sub -> filterDeadline == null || (sub.getAnswerDetailsJson() != null && sub.getAnswerDetailsJson().contains(filterDeadline)))
            .toList();

        List<Map<String, Object>> submittedStudents = new ArrayList<>();
        List<Map<String, Object>> notSubmittedStudents = new ArrayList<>();
        List<Map<String, Object>> queuedStudents = new ArrayList<>();

        for (EnrolledStudent student : enrolledStudents) {
            ExamSubmission submission = submissions.stream()
                .filter(sub -> sub.getStudentEmail().equalsIgnoreCase(student.getStudentEmail()))
                .findFirst().orElse(null);
            Map<String, Object> item = new HashMap<>();
            item.put("studentName", student.getStudentName());
            item.put("studentEmail", student.getStudentEmail());
            item.put("examName", filterExamName != null ? filterExamName : "");
            item.put("activityType", filterActivityType != null ? filterActivityType : "Quiz");
            item.put("deadline", filterDeadline != null ? filterDeadline : "");
            item.put("lastSubmittedAt", submission != null && submission.getSubmittedAt() != null ? submission.getSubmittedAt().toString() : "-");

            // Only mark as 'Submitted' if isSubmissionCompleted(submission) is true
            if (submission != null && isSubmissionCompleted(submission)) {
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
        int totalTrackedCount = submittedCount + notSubmittedCount + queuedCount;

        model.addAttribute("subject", subject);
        model.addAttribute("submittedStudents", submittedStudents);
        model.addAttribute("notSubmittedStudents", notSubmittedStudents);
        model.addAttribute("queuedStudents", queuedStudents);
        model.addAttribute("submittedCount", submittedCount);
        model.addAttribute("notSubmittedCount", notSubmittedCount);
        model.addAttribute("queuedCount", queuedCount);
        model.addAttribute("totalTrackedCount", totalTrackedCount);

        model.addAttribute("filterExamName", filterExamName);
        model.addAttribute("filterActivityType", filterActivityType);
        model.addAttribute("filterTimeLimit", filterTimeLimit);
        model.addAttribute("filterDeadline", filterDeadline);
        model.addAttribute("activeQuizFilter",
            filterExamName != null || filterActivityType != null || filterTimeLimit != null || filterDeadline != null);

        return "subject-distribution-students";
    }

    @GetMapping("/profile")
    public String profile() {
        return "teacher-profile";
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase().trim();
    }

    private List<Map<String, Object>> buildQuizDistributionSummary(List<DistributedExam> distributedExams) {
        List<DistributedExam> sorted = new ArrayList<>(distributedExams == null ? new ArrayList<>() : distributedExams);
        sorted.sort(Comparator.comparing(DistributedExam::getDistributedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (DistributedExam distributedExam : sorted) {
            if (distributedExam == null || distributedExam.getExamName() == null || distributedExam.getExamName().isBlank()) {
                continue;
            }

            String deadlineRaw = distributedExam.getDeadline() == null ? "" : distributedExam.getDeadline();
            String groupKey = distributedExam.getExamName() + "|"
                + String.valueOf(distributedExam.getActivityType()) + "|"
                + String.valueOf(distributedExam.getTimeLimit()) + "|"
                + deadlineRaw;

            Map<String, Object> row = grouped.computeIfAbsent(groupKey, key -> {
                Map<String, Object> created = new HashMap<>();
                Integer safeTimeLimit = distributedExam.getTimeLimit();
                created.put("examName", distributedExam.getExamName());
                created.put("subject", distributedExam.getSubject() == null ? "" : distributedExam.getSubject());
                created.put("activityType", distributedExam.getActivityType() == null ? "Quiz" : distributedExam.getActivityType());
                created.put("timeLimit", safeTimeLimit != null ? safeTimeLimit : 60);
                created.put("deadline", formatDeadline(deadlineRaw));
                created.put("filterExamName", distributedExam.getExamName());
                created.put("filterActivityType", distributedExam.getActivityType());
                created.put("filterTimeLimit", distributedExam.getTimeLimit());
                created.put("filterDeadline", deadlineRaw == null ? "" : deadlineRaw);
                created.put("assignedCount", 0);
                created.put("submittedCount", 0);
                created.put("notSubmittedCount", 0);
                return created;
            });

            int assigned = (int) row.getOrDefault("assignedCount", 0) + 1;
            row.put("assignedCount", assigned);

            if (distributedExam.isSubmitted()) {
                int submittedCount = (int) row.getOrDefault("submittedCount", 0) + 1;
                row.put("submittedCount", submittedCount);
            }
        }

        for (Map<String, Object> row : grouped.values()) {
            int assigned = (int) row.getOrDefault("assignedCount", 0);
            int submitted = (int) row.getOrDefault("submittedCount", 0);
            row.put("notSubmittedCount", Math.max(0, assigned - submitted));
        }

        return new ArrayList<>(grouped.values());
    }

    private String formatDeadline(String deadlineRaw) {
        if (deadlineRaw == null || deadlineRaw.isBlank()) {
            return "No deadline";
        }
        try {
            return LocalDateTime.parse(deadlineRaw).format(DEADLINE_DISPLAY_FORMAT);
        } catch (Exception exception) {
            return deadlineRaw;
        }
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
            boolean headerChecked = false;
            int questionColumn = 0;
            int answerColumn = 5;
            int difficultyColumn = -1;
            List<Integer> choiceColumns = new ArrayList<>();
            choiceColumns.add(1);
            choiceColumns.add(2);
            choiceColumns.add(3);
            choiceColumns.add(4);

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                List<String> columns = splitCsvLine(line);
                if (!headerChecked) {
                    headerChecked = true;

                    List<String> normalizedHeaders = new ArrayList<>();
                    for (String col : columns) {
                        normalizedHeaders.add(normalize(col));
                    }

                    int detectedQuestionColumn = -1;
                    int detectedAnswerColumn = -1;
                    int detectedDifficultyColumn = -1;
                    List<Integer> detectedChoiceColumns = new ArrayList<>();

                    for (int index = 0; index < normalizedHeaders.size(); index++) {
                        String header = normalizedHeaders.get(index);
                        if (header.contains("question")) {
                            detectedQuestionColumn = index;
                        }
                        if (header.contains("answer") || header.equals("key")) {
                            detectedAnswerColumn = index;
                        }
                        if (header.contains("difficulty") || header.contains("level")) {
                            detectedDifficultyColumn = index;
                        }
                        if (header.contains("choice") || header.matches("option\\s*[a-z0-9]+") || header.matches("[a-d]")) {
                            detectedChoiceColumns.add(index);
                        }
                    }

                    String first = normalizedHeaders.isEmpty() ? "" : normalizedHeaders.get(0);
                    boolean looksLikeHeader = detectedQuestionColumn >= 0
                        || detectedAnswerColumn >= 0
                        || !detectedChoiceColumns.isEmpty()
                        || first.equals("id")
                        || first.equals("number")
                        || first.equals("no");

                    if (looksLikeHeader) {
                        if (detectedQuestionColumn >= 0) {
                            questionColumn = detectedQuestionColumn;
                        } else if ((first.equals("id") || first.equals("number") || first.equals("no")) && normalizedHeaders.size() > 1) {
                            questionColumn = 1;
                        }

                        if (detectedAnswerColumn >= 0) {
                            answerColumn = detectedAnswerColumn;
                        }

                        if (detectedDifficultyColumn >= 0) {
                            difficultyColumn = detectedDifficultyColumn;
                        }

                        if (!detectedChoiceColumns.isEmpty()) {
                            choiceColumns = detectedChoiceColumns;
                        } else {
                            choiceColumns = new ArrayList<>();
                            int choiceStart = Math.min(questionColumn + 1, Math.max(columns.size() - 1, 0));
                            int choiceEnd = detectedAnswerColumn > 0 ? detectedAnswerColumn : Math.min(choiceStart + 4, columns.size());
                            for (int index = choiceStart; index < choiceEnd; index++) {
                                choiceColumns.add(index);
                            }
                        }
                        continue;
                    }
                }

                if (columns.isEmpty() || questionColumn < 0 || questionColumn >= columns.size()) {
                    continue;
                }

                Map<String, Object> question = new HashMap<>();
                String questionText = columns.get(questionColumn) == null ? "" : columns.get(questionColumn).trim();
                if (looksLikePlaceholderQuestion(questionText, null, null)) {
                    questionText = pickBestQuestionCandidate(columns, questionColumn, answerColumn);
                }
                if (questionText.isBlank()) {
                    continue;
                }
                question.put("question", questionText);

                List<String> choices = new ArrayList<>();
                for (Integer index : choiceColumns) {
                    if (index == null || index < 0 || index >= columns.size()) {
                        continue;
                    }
                    String choiceValue = normalizeMathSymbols(columns.get(index));
                    if (!choiceValue.isBlank()) {
                        choices.add(choiceValue);
                    }
                }
                question.put("choices", choices);

                if (answerColumn >= 0 && answerColumn < columns.size()) {
                    question.put("answer", normalizeMathSymbols(columns.get(answerColumn)));
                }

                if (difficultyColumn >= 0 && difficultyColumn < columns.size()) {
                    String normalizedDifficulty = normalizeDifficulty(columns.get(difficultyColumn));
                    if (!normalizedDifficulty.isBlank()) {
                        question.put("difficulty", normalizedDifficulty);
                    }
                }

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

    private boolean isOwner(Principal principal, String ownerEmail) {
        String teacherEmail = principal != null ? principal.getName() : "";
        return !teacherEmail.isBlank() && ownerEmail != null && teacherEmail.equalsIgnoreCase(ownerEmail);
    }

    private String resolveQuestionDisplay(Map<String, Object> questionRow, String difficulty, String answer) {
        return resolveQuestionCandidate(questionRow, difficulty, answer, true);
    }

    private String resolveQuestionCandidate(Map<String, Object> questionRow,
                                            String difficulty,
                                            String answer,
                                            boolean allowAnswerFallback) {
        String rawText = String.valueOf(questionRow.getOrDefault("question", "")).trim();
        String cleaned = rawText.startsWith("[TEXT_INPUT]") ? rawText.substring("[TEXT_INPUT]".length()).trim() : rawText;
        List<String> candidates = new ArrayList<>();
        candidates.add(cleaned);

        Object questionTextField = questionRow.get("question_text");
        if (questionTextField != null) {
            candidates.add(String.valueOf(questionTextField).trim());
        }
        Object questionTextCamel = questionRow.get("questionText");
        if (questionTextCamel != null) {
            candidates.add(String.valueOf(questionTextCamel).trim());
        }
        Object textField = questionRow.get("text");
        if (textField != null) {
            candidates.add(String.valueOf(textField).trim());
        }

        Object choicesObj = questionRow.get("choices");
        if (choicesObj instanceof List<?> choices) {
            for (Object choice : choices) {
                candidates.add(String.valueOf(choice).trim());
            }
        } else if (choicesObj instanceof String choicesText) {
            for (String part : choicesText.split("\\r?\\n|,")) {
                candidates.add(part.trim());
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
                candidates.add(String.valueOf(value).trim());
            }
        }

        String bestCandidate = "";
        for (String candidate : candidates) {
            if (!looksLikePlaceholderQuestion(candidate, difficulty, answer) && candidate.length() > bestCandidate.length()) {
                bestCandidate = candidate;
            }
        }

        if (!bestCandidate.isBlank()) {
            return bestCandidate;
        }

        if (allowAnswerFallback && answer != null) {
            String answerText = answer.trim();
            if (answerText.length() > 12 && !answerText.equalsIgnoreCase("MANUAL_GRADE")) {
                return answerText;
            }
        }

        return "";
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

        if (List.of("id", "no", "number", "question", "questions", "answer", "answers", "difficulty")
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

    private String normalizeMathSymbols(String text) {
        if (text == null) {
            return "";
        }

        String normalized = text;

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

        Pattern decimalEntity = Pattern.compile("&#(\\d+);");
        Matcher decimalMatcher = decimalEntity.matcher(normalized);
        StringBuffer decimalBuffer = new StringBuffer();
        while (decimalMatcher.find()) {
            int codePoint = Integer.parseInt(decimalMatcher.group(1));
            decimalMatcher.appendReplacement(decimalBuffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        decimalMatcher.appendTail(decimalBuffer);
        normalized = decimalBuffer.toString();

        Pattern hexEntity = Pattern.compile("&#x([0-9a-fA-F]+);");
        Matcher hexMatcher = hexEntity.matcher(normalized);
        StringBuffer hexBuffer = new StringBuffer();
        while (hexMatcher.find()) {
            int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
            hexMatcher.appendReplacement(hexBuffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        hexMatcher.appendTail(hexBuffer);

        return hexBuffer.toString().trim();
    }
}
