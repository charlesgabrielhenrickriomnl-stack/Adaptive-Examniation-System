package com.exam.Controller;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.exam.entity.DistributedExam;
import com.exam.entity.EnrolledStudent;
import com.exam.entity.ExamSubmission;
import com.exam.entity.OriginalProcessedPaper;
import com.exam.entity.Subject;
import com.exam.repository.DistributedExamRepository;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.ExamSubmissionRepository;
import com.exam.repository.OriginalProcessedPaperRepository;
import com.exam.repository.SubjectRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/student")
public class StudentController {
    private static final DateTimeFormatter DEADLINE_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
    private static final Pattern QUESTION_PARAM_PATTERN = Pattern.compile("q\\d+");
    private static final String ACTIVE_EXAM_SESSION_KEY = "ACTIVE_DISTRIBUTED_EXAM_ID";

    private final ExamSubmissionRepository examSubmissionRepository;
    private final EnrolledStudentRepository enrolledStudentRepository;
    private final SubjectRepository subjectRepository;
    private final DistributedExamRepository distributedExamRepository;
    private final OriginalProcessedPaperRepository originalProcessedPaperRepository;
    private final Gson gson = new Gson();

    public StudentController(ExamSubmissionRepository examSubmissionRepository,
                             EnrolledStudentRepository enrolledStudentRepository,
                             SubjectRepository subjectRepository,
                             DistributedExamRepository distributedExamRepository,
                             OriginalProcessedPaperRepository originalProcessedPaperRepository) {
        this.examSubmissionRepository = examSubmissionRepository;
        this.enrolledStudentRepository = enrolledStudentRepository;
        this.subjectRepository = subjectRepository;
        this.distributedExamRepository = distributedExamRepository;
        this.originalProcessedPaperRepository = originalProcessedPaperRepository;
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Principal principal, HttpSession session) {
        String studentEmail = getStudentEmail(principal);
        String activeExamRedirect = redirectToActiveExamIfAny(studentEmail, session);
        if (activeExamRedirect != null) {
            return activeExamRedirect;
        }

        List<EnrolledStudent> enrollments = enrolledStudentRepository.findByStudentEmail(studentEmail);
        List<Map<String, Object>> enrollmentCards = enrollments.stream().map(this::toEnrollmentCard).toList();

        List<ExamSubmission> allSubmissions = examSubmissionRepository.findByStudentEmail(studentEmail).stream()
            .sorted(Comparator.comparing(ExamSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(Collectors.toList());

        List<Map<String, Object>> subjectCards = buildSubjectCards(enrollments, studentEmail, allSubmissions);

        model.addAttribute("studentEmail", studentEmail);
        model.addAttribute("hasSubjects", !subjectCards.isEmpty());
        model.addAttribute("subjectCards", subjectCards);
        model.addAttribute("finalizedNotifications", allSubmissions.stream()
            .filter(ExamSubmission::isGraded)
            .limit(5)
            .toList());
        model.addAttribute("fullName", studentEmail);
        model.addAttribute("enrollments", enrollmentCards);
        model.addAttribute("allSubmissions", allSubmissions);
        model.addAttribute("recentSubmissions", allSubmissions.stream().limit(5).toList());
        model.addAttribute("totalAttempts", allSubmissions.size());
        double avgScore = allSubmissions.isEmpty() ? 0.0 : allSubmissions.stream().mapToDouble(ExamSubmission::getPercentage).average().orElse(0.0);
        model.addAttribute("avgScore", String.format("%.1f", avgScore));
        long passedCount = allSubmissions.stream().filter(sub -> sub.getPercentage() >= 60.0).count();
        long failedCount = allSubmissions.stream().filter(sub -> sub.getPercentage() < 60.0).count();
        model.addAttribute("passedCount", passedCount);
        model.addAttribute("failedCount", failedCount);
        double bestScore = allSubmissions.stream().mapToDouble(ExamSubmission::getPercentage).max().orElse(0.0);
        model.addAttribute("bestScore", String.format("%.1f", bestScore));
        model.addAttribute("hasSubmissions", !allSubmissions.isEmpty());
        return "student-dashboard";
    }

    @GetMapping("/all-attempts")
    public String allAttempts(Model model, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        List<ExamSubmission> allSubmissions = examSubmissionRepository.findByStudentEmail(studentEmail).stream()
            .sorted(Comparator.comparing(ExamSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
        model.addAttribute("allSubmissions", allSubmissions);
        model.addAttribute("hasSubmissions", !allSubmissions.isEmpty());
        return "student-all-attempts";
    }

    @GetMapping("/classroom/{id}")
    public String classroom(@PathVariable("id") Long subjectId, Model model, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Optional<EnrolledStudent> enrollmentOpt = enrolledStudentRepository.findByStudentEmail(studentEmail).stream()
            .filter(item -> subjectId.equals(item.getSubjectId()))
            .findFirst();
        if (enrollmentOpt.isEmpty()) {
            return "redirect:/student/dashboard";
        }

        Subject subject = subjectRepository.findById(subjectId).orElse(null);
        String subjectName = subject != null ? subject.getSubjectName() : enrollmentOpt.get().getSubjectName();
        String subjectDescription = subject != null ? subject.getDescription() : "";
        String teacherEmail = subject != null ? subject.getTeacherEmail() : enrollmentOpt.get().getTeacherEmail();

        List<ExamSubmission> submissionsBySubject = examSubmissionRepository.findByStudentEmail(studentEmail).stream()
            .filter(sub -> sameText(sub.getSubject(), subjectName))
            .sorted(Comparator.comparing(ExamSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        List<Map<String, Object>> pendingExams = distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
            .filter(item -> sameText(item.getSubject(), subjectName))
            .filter(item -> !isMissedExam(item))
            .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::toPendingExamCard)
            .toList();

        model.addAttribute("subjectName", subjectName);
        model.addAttribute("subjectDescription", subjectDescription == null ? "" : subjectDescription);
        model.addAttribute("teacherEmail", teacherEmail == null ? "" : teacherEmail);
        model.addAttribute("pendingExams", pendingExams);
        model.addAttribute("hasHistory", !submissionsBySubject.isEmpty());
        model.addAttribute("submissionCount", submissionsBySubject.size());
        return "student-classroom";
    }

    @GetMapping("/take-exam")
    public String takeExamFirstAvailable(Principal principal, HttpSession session) {
        String studentEmail = getStudentEmail(principal);
        Optional<DistributedExam> firstPending = distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
            .filter(item -> !isMissedExam(item))
            .findFirst();
        if (firstPending.isEmpty()) {
            return "redirect:/student/dashboard";
        }
        session.setAttribute(ACTIVE_EXAM_SESSION_KEY, firstPending.get().getId());
        return "redirect:/student/take-exam/" + firstPending.get().getId();
    }

    @GetMapping("/take-exam/{distributedExamId}")
    public String takeExam(@PathVariable Long distributedExamId, Model model, Principal principal, HttpSession session) {
        String studentEmail = getStudentEmail(principal);
        Optional<DistributedExam> distributedOpt = distributedExamRepository.findById(distributedExamId);
        if (distributedOpt.isEmpty()) {
            return "redirect:/student/dashboard";
        }

        DistributedExam distributedExam = distributedOpt.get();
        if (!sameText(distributedExam.getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        if (isMissedExam(distributedExam)) {
            clearActiveExamSession(session, distributedExam.getId());
            return "redirect:/student/dashboard";
        }

        if (distributedExam.isSubmitted()) {
            clearActiveExamSession(session, distributedExam.getId());
            Optional<ExamSubmission> existingSubmission = examSubmissionRepository
                .findByStudentEmailAndExamNameAndSubject(studentEmail, distributedExam.getExamName(), distributedExam.getSubject())
                .stream()
                .max(Comparator.comparing(ExamSubmission::getSubmittedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            return existingSubmission
                .map(sub -> "redirect:/student/results/" + sub.getId())
                .orElse("redirect:/student/dashboard");
        }

        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(distributedExam.getExamId());
        if (paperOpt.isEmpty()) {
            return "redirect:/student/dashboard";
        }

        session.setAttribute(ACTIVE_EXAM_SESSION_KEY, distributedExam.getId());

        OriginalProcessedPaper paper = paperOpt.get();
        List<Map<String, Object>> questionRows = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> difficultyMap = parseSimpleMapJson(paper.getDifficultiesJson());
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());

        List<String> examQuestions = new ArrayList<>();
        List<String> difficulties = new ArrayList<>();
        for (int index = 0; index < questionRows.size(); index++) {
            String key = String.valueOf(index + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String answer = answerKeyMap.getOrDefault(key, "");
            String displayQuestion = resolveQuestionDisplay(questionRows.get(index), difficulty, answer);
            if (displayQuestion.isBlank()) {
                continue;
            }
            examQuestions.add(displayQuestion);
            difficulties.add(difficulty.isBlank() ? "Medium" : difficulty);
        }

        List<ExamSubmission> previousSubmissions = examSubmissionRepository
            .findByStudentEmailAndExamNameAndSubject(studentEmail, distributedExam.getExamName(), distributedExam.getSubject());

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("email", studentEmail);
        userInfo.put("fullName", studentEmail);

        Map<String, Object> examInfo = new HashMap<>();
        examInfo.put("examId", distributedExam.getExamId());
        examInfo.put("distributedExamId", distributedExam.getId());
        examInfo.put("examName", distributedExam.getExamName());
        examInfo.put("subject", distributedExam.getSubject());
        examInfo.put("activityType", distributedExam.getActivityType());
        examInfo.put("timeLimit", distributedExam.getTimeLimit() == null ? 60 : distributedExam.getTimeLimit());
        examInfo.put("deadline", formatDeadline(distributedExam.getDeadline()));
        examInfo.put("startTimeMillis", System.currentTimeMillis());

        model.addAttribute("exam", examQuestions);
        model.addAttribute("difficulties", difficulties);
        model.addAttribute("examInfo", examInfo);
        model.addAttribute("userInfo", userInfo);
        model.addAttribute("previouslySubmitted", !previousSubmissions.isEmpty());
        model.addAttribute("previousSubmissionCount", previousSubmissions.size());
        model.addAttribute("previousSubmittedAt", previousSubmissions.isEmpty() ? null : previousSubmissions.stream()
            .map(ExamSubmission::getSubmittedAt)
            .filter(value -> value != null)
            .max(LocalDateTime::compareTo)
            .orElse(null));
        return "student-exam-paginated";
    }

    @PostMapping("/submit")
    public String submit(@RequestParam Map<String, String> formData, Principal principal, Model model, HttpSession session) {
        String studentEmail = getStudentEmail(principal);
        Long distributedExamId = parseLong(formData.get("distributedExamId"));
        String examId = formData.get("examId");

        DistributedExam distributedExam = null;
        if (distributedExamId != null) {
            distributedExam = distributedExamRepository.findById(distributedExamId).orElse(null);
        }

        if (distributedExam == null && examId != null && !examId.isBlank()) {
            distributedExam = distributedExamRepository.findByExamId(examId).stream()
                .filter(item -> sameText(item.getStudentEmail(), studentEmail) && !item.isSubmitted())
                .findFirst()
                .orElse(null);
        }

        if (distributedExam == null || !sameText(distributedExam.getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        if (isMissedExam(distributedExam)) {
            clearActiveExamSession(session, distributedExam.getId());
            return "redirect:/student/dashboard";
        }

        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(distributedExam.getExamId());
        if (paperOpt.isEmpty()) {
            return "redirect:/student/dashboard";
        }

        OriginalProcessedPaper paper = paperOpt.get();
        List<Map<String, Object>> questionRows = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());
        Map<String, String> difficultyMap = parseSimpleMapJson(paper.getDifficultiesJson());

        int totalQuestions = 0;
        int score = 0;
        boolean hasOpenEnded = false;
        List<Map<String, Object>> details = new ArrayList<>();
        List<Map<String, Object>> studentAnswers = new ArrayList<>();

        for (int index = 0; index < questionRows.size(); index++) {
            String key = String.valueOf(index + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String correctAnswer = normalizeAnswer(answerKeyMap.getOrDefault(key, ""));
            String displayQuestion = resolveQuestionDisplay(questionRows.get(index), difficulty, correctAnswer);
            if (displayQuestion.isBlank()) {
                continue;
            }

            totalQuestions++;
            String answerKey = "q" + (index + 1);
            String studentAnswer = formData.getOrDefault(answerKey, "").trim();
            boolean openEnded = displayQuestion.startsWith("[TEXT_INPUT]");
            boolean isCorrect = false;

            if (openEnded) {
                hasOpenEnded = true;
                correctAnswer = "MANUAL_GRADE";
            } else {
                isCorrect = !studentAnswer.isBlank() && normalizeAnswer(studentAnswer).equals(normalizeAnswer(correctAnswer));
                if (isCorrect) {
                    score++;
                }
            }

            Map<String, Object> answerItem = new HashMap<>();
            answerItem.put("questionNumber", index + 1);
            answerItem.put("question", displayQuestion.replace("[TEXT_INPUT]", "").trim());
            answerItem.put("studentAnswer", studentAnswer.isBlank() ? "No answer" : studentAnswer);
            answerItem.put("correctAnswer", correctAnswer.isBlank() ? "N/A" : correctAnswer);
            answerItem.put("isCorrect", isCorrect);
            answerItem.put("difficulty", difficulty.isBlank() ? "Medium" : difficulty);
            answerItem.put("type", openEnded ? "OPEN_ENDED" : "MULTIPLE_CHOICE");
            details.add(answerItem);

            Map<String, Object> submittedAnswer = new HashMap<>();
            submittedAnswer.put("questionNumber", index + 1);
            submittedAnswer.put("answer", studentAnswer);
            studentAnswers.add(submittedAnswer);
        }

        if (totalQuestions == 0) {
            return "redirect:/student/dashboard";
        }

        double percentage = (score * 100.0) / totalQuestions;

        ExamSubmission submission = new ExamSubmission();
        submission.setStudentEmail(studentEmail);
        submission.setExamName(distributedExam.getExamName());
        submission.setSubject(distributedExam.getSubject());
        submission.setActivityType(distributedExam.getActivityType());
        submission.setScore(score);
        submission.setTotalQuestions(totalQuestions);
        submission.setPercentage(percentage);
        submission.setCurrentQuestion(totalQuestions);
        submission.setTimeLimit(distributedExam.getTimeLimit());
        submission.setDifficulty("Mixed");
        submission.setResultsReleased(Boolean.FALSE);
        submission.setGraded(!hasOpenEnded);
        submission.setSubmittedAt(LocalDateTime.now());
        submission.setTopicMastery(percentage);
        submission.setDifficultyResilience(percentage);
        submission.setAccuracy(percentage);
        submission.setTimeEfficiency(Math.min(100.0, Math.max(40.0, 100.0 - (totalQuestions * 1.2))));
        submission.setConfidence(Math.min(100.0, Math.max(50.0, percentage + 10.0)));
        submission.setPerformanceCategory(performanceCategory(percentage));

        Map<String, Object> payload = new HashMap<>();
        payload.put("submitted", true);
        payload.put("examId", distributedExam.getExamId());
        payload.put("distributedExamId", distributedExam.getId());
        payload.put("deadline", distributedExam.getDeadline());
        payload.put("studentAnswers", studentAnswers);
        payload.put("finalAnswers", details);
        submission.setAnswerDetailsJson(gson.toJson(payload));

        examSubmissionRepository.save(submission);

        distributedExam.setSubmitted(true);
        distributedExamRepository.save(distributedExam);
        clearActiveExamSession(session, distributedExam.getId());

        model.addAttribute("submission", submission);
        return "student-submission-confirmation";
    }

    @GetMapping("/results/{id}")
    public String results(@PathVariable Long id, Model model, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty() || !sameText(submissionOpt.get().getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        ExamSubmission submission = submissionOpt.get();
        if (!submission.isResultsReleased()) {
            model.addAttribute("infoMessage", "Your teacher has not released the final results yet.");
        }

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("topicMastery", submission.getTopicMastery());
        analytics.put("difficultyResilience", submission.getDifficultyResilience());
        analytics.put("accuracy", submission.getAccuracy());
        analytics.put("timeEfficiency", submission.getTimeEfficiency());
        analytics.put("confidence", submission.getConfidence());
        analytics.put("performanceCategory", submission.getPerformanceCategory());

        model.addAttribute("submission", submission);
        model.addAttribute("score", submission.getScore());
        model.addAttribute("total", submission.getTotalQuestions());
        model.addAttribute("percentage", submission.getPercentage());
        model.addAttribute("analytics", analytics);
        return "student-results";
    }

    @GetMapping("/performance-analytics/{id}")
    public String performanceAnalytics(@PathVariable Long id, Model model, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty() || !sameText(submissionOpt.get().getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        ExamSubmission submission = submissionOpt.get();
        List<String> strengths = new ArrayList<>();
        List<String> weaknesses = new ArrayList<>();

        classifyMetric("Topic Mastery", submission.getTopicMastery(), strengths, weaknesses);
        classifyMetric("Difficulty Resilience", submission.getDifficultyResilience(), strengths, weaknesses);
        classifyMetric("Accuracy", submission.getAccuracy(), strengths, weaknesses);
        classifyMetric("Time Efficiency", submission.getTimeEfficiency(), strengths, weaknesses);
        classifyMetric("Confidence", submission.getConfidence(), strengths, weaknesses);

        List<String> recommendations = new ArrayList<>();
        if (weaknesses.isEmpty()) {
            recommendations.add("Maintain your current study strategy and continue practicing consistently.");
        } else {
            recommendations.add("Prioritize review sessions for: " + String.join(", ", weaknesses) + ".");
            recommendations.add("Take short timed practice quizzes to improve speed and confidence.");
            recommendations.add("Review incorrect answers and write a one-line correction for each mistake.");
        }

        model.addAttribute("submission", submission);
        model.addAttribute("strengths", strengths);
        model.addAttribute("weaknesses", weaknesses);
        model.addAttribute("recommendations", recommendations);
        return "student-performance-analytics";
    }

    @GetMapping("/view-exam/{id}")
    public String viewExam(@PathVariable Long id, Model model, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty() || !sameText(submissionOpt.get().getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        ExamSubmission submission = submissionOpt.get();
        model.addAttribute("submission", submission);
        model.addAttribute("answerDetails", extractAnswerDetails(submission.getAnswerDetailsJson()));
        return "student-view-exam";
    }

    @GetMapping("/download-result/{id}")
    public ResponseEntity<byte[]> downloadResult(@PathVariable Long id, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty() || !sameText(submissionOpt.get().getStudentEmail(), studentEmail)) {
            return ResponseEntity.status(403).build();
        }

        ExamSubmission submission = submissionOpt.get();
        StringBuilder csv = new StringBuilder();
        csv.append("Student,Exam,Subject,Type,Score,Total,Percentage,Submitted At\n");
        csv.append(csvCell(submission.getStudentEmail())).append(',')
            .append(csvCell(submission.getExamName())).append(',')
            .append(csvCell(submission.getSubject())).append(',')
            .append(csvCell(submission.getActivityType())).append(',')
            .append(submission.getScore()).append(',')
            .append(submission.getTotalQuestions()).append(',')
            .append(String.format(Locale.US, "%.2f", submission.getPercentage())).append(',')
            .append(csvCell(submission.getSubmittedAt() == null ? "" : submission.getSubmittedAt().toString()))
            .append('\n');

        String filename = (submission.getExamName() == null || submission.getExamName().isBlank())
            ? "result.csv"
            : submission.getExamName().replaceAll("[^a-zA-Z0-9._-]", "_") + "-result.csv";

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/profile")
    public String profile(Model model, Principal principal) {
        String studentEmail = getStudentEmail(principal);
        model.addAttribute("studentEmail", studentEmail);
        return "student-profile";
    }

    private String getStudentEmail(Principal principal) {
        return principal != null && principal.getName() != null ? principal.getName() : "student";
    }

    private Map<String, Object> toEnrollmentCard(EnrolledStudent item) {
        Map<String, Object> card = new HashMap<>();
        card.put("subjectId", item.getSubjectId());
        card.put("subjectName", item.getSubjectName());
        card.put("teacherEmail", item.getTeacherEmail());
        return card;
    }

    private List<Map<String, Object>> buildSubjectCards(List<EnrolledStudent> enrollments,
                                                         String studentEmail,
                                                         List<ExamSubmission> allSubmissions) {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (EnrolledStudent enrollment : enrollments) {
            if (enrollment.getSubjectId() == null) {
                continue;
            }

            Subject subject = subjectRepository.findById(enrollment.getSubjectId()).orElse(null);
            String subjectName = subject != null ? subject.getSubjectName() : enrollment.getSubjectName();
            String subjectDescription = subject != null ? subject.getDescription() : "";
            String teacherEmail = subject != null ? subject.getTeacherEmail() : enrollment.getTeacherEmail();

            List<Map<String, Object>> activities = new ArrayList<>();

            distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
                .filter(item -> sameText(item.getSubject(), subjectName))
                .sorted(Comparator.comparing(DistributedExam::getDistributedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(item -> {
                    boolean missed = isMissedExam(item);
                    Map<String, Object> activity = new HashMap<>();
                    activity.put("name", item.getExamName());
                    activity.put("type", item.getActivityType() == null ? "Exam" : item.getActivityType());
                    activity.put("status", missed ? "missed" : "pending");
                    activity.put("color", missed ? "danger" : "warning");
                    activity.put("questionCount", countQuestions(item.getExamId()));
                    activity.put("timeLimit", item.getTimeLimit() == null ? 60 : item.getTimeLimit());
                    activity.put("deadline", formatDeadline(item.getDeadline()));
                    activities.add(activity);
                });

            allSubmissions.stream()
                .filter(sub -> sameText(sub.getSubject(), subjectName))
                .sorted(Comparator.comparing(ExamSubmission::getSubmittedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(sub -> {
                    Map<String, Object> activity = new HashMap<>();
                    activity.put("name", sub.getExamName());
                    activity.put("type", sub.getActivityType() == null ? "Exam" : sub.getActivityType());
                    activity.put("status", "completed");
                    activity.put("color", scoreColor(sub.getPercentage()));
                    activity.put("submittedAt", sub.getSubmittedAt());
                    activities.add(activity);
                });

            Map<String, Object> card = new HashMap<>();
            card.put("id", enrollment.getSubjectId());
            card.put("name", subjectName == null ? "Subject" : subjectName);
            card.put("description", subjectDescription == null ? "" : subjectDescription);
            card.put("teacherEmail", teacherEmail == null ? "" : teacherEmail);
            card.put("activities", activities);
            card.put("activityCount", activities.size());
            cards.add(card);
        }
        return cards;
    }

    private Map<String, Object> toPendingExamCard(DistributedExam item) {
        Map<String, Object> card = new HashMap<>();
        card.put("name", item.getExamName());
        card.put("type", item.getActivityType() == null ? "Exam" : item.getActivityType());
        card.put("questionCount", countQuestions(item.getExamId()));
        card.put("timeLimit", item.getTimeLimit() == null ? 60 : item.getTimeLimit());
        card.put("deadline", formatDeadline(item.getDeadline()));
        card.put("startUrl", "/student/take-exam/" + item.getId());
        return card;
    }

    private int countQuestions(String examId) {
        if (examId == null || examId.isBlank()) {
            return 0;
        }
        return originalProcessedPaperRepository.findByExamId(examId)
            .map(paper -> parseQuestionsJson(paper.getOriginalQuestionsJson()).size())
            .orElse(0);
    }

    private String scoreColor(double percentage) {
        if (percentage >= 75.0) {
            return "success";
        }
        if (percentage >= 50.0) {
            return "warning";
        }
        return "danger";
    }

    private String performanceCategory(double percentage) {
        if (percentage >= 90) {
            return "Excellent";
        }
        if (percentage >= 75) {
            return "Good";
        }
        if (percentage >= 60) {
            return "Fair";
        }
        return "Needs Improvement";
    }

    private String formatDeadline(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return LocalDateTime.parse(raw).format(DEADLINE_DISPLAY_FORMAT);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private List<Map<String, Object>> parseQuestionsJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> parsed = gson.fromJson(json,
                new TypeToken<List<Map<String, Object>>>() { }.getType());
            return parsed == null ? new ArrayList<>() : parsed;
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }

    private String resolveQuestionDisplay(Map<String, Object> questionRow, String difficulty, String answer) {
        String rawText = String.valueOf(questionRow.getOrDefault("question", "")).trim();
        String cleaned = rawText.startsWith("[TEXT_INPUT]")
            ? "[TEXT_INPUT]" + rawText.substring("[TEXT_INPUT]".length()).trim()
            : rawText;

        if (!cleaned.isBlank() && !looksLikePlaceholderQuestion(cleaned, difficulty, answer)) {
            return cleaned;
        }

        Object choicesObj = questionRow.get("choices");
        List<String> choices = new ArrayList<>();
        if (choicesObj instanceof List<?> choiceList) {
            for (Object choice : choiceList) {
                if (choice != null) {
                    String normalized = String.valueOf(choice).trim();
                    if (!normalized.isBlank()) {
                        choices.add(normalized);
                    }
                }
            }
        }

        String stem = String.valueOf(questionRow.getOrDefault("question_text", questionRow.getOrDefault("text", ""))).trim();
        if (stem.isBlank()) {
            stem = cleaned.replace("[TEXT_INPUT]", "").trim();
        }

        if (stem.isBlank() || looksLikePlaceholderQuestion(stem, difficulty, answer)) {
            return "";
        }

        if (cleaned.startsWith("[TEXT_INPUT]")) {
            return "[TEXT_INPUT]" + stem;
        }

        if (choices.isEmpty()) {
            return stem;
        }

        StringBuilder builder = new StringBuilder(stem);
        char letter = 'A';
        for (String choice : choices) {
            builder.append("\n").append(letter++).append(") ").append(choice);
        }
        return builder.toString();
    }

    private boolean looksLikePlaceholderQuestion(String value, String difficulty, String answer) {
        if (value == null) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.matches("\\d+")) {
            return true;
        }
        if (List.of("question", "questions", "answer", "answers", "difficulty", "id", "number", "no").contains(normalized)) {
            return true;
        }
        if (difficulty != null && normalized.equals(difficulty.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (answer != null && !answer.isBlank() && normalized.equals(answer.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        return false;
    }

    private String normalizeDifficulty(String value) {
        if (value == null) {
            return "Medium";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("easy")) {
            return "Easy";
        }
        if (normalized.startsWith("hard")) {
            return "Hard";
        }
        return "Medium";
    }

    private String normalizeAnswer(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isDeadlinePassed(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            LocalDateTime deadline = LocalDateTime.parse(raw.trim());
            LocalDateTime now = LocalDateTime.now();
            return !deadline.isAfter(now);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isMissedExam(DistributedExam distributedExam) {
        if (distributedExam == null || distributedExam.isSubmitted()) {
            return false;
        }
        return isDeadlinePassed(distributedExam.getDeadline());
    }

    private String redirectToActiveExamIfAny(String studentEmail, HttpSession session) {
        if (session == null) {
            return null;
        }

        Object rawActiveId = session.getAttribute(ACTIVE_EXAM_SESSION_KEY);
        if (rawActiveId == null) {
            return null;
        }

        Long activeId = parseLong(String.valueOf(rawActiveId));
        if (activeId == null) {
            session.removeAttribute(ACTIVE_EXAM_SESSION_KEY);
            return null;
        }

        Optional<DistributedExam> activeExamOpt = distributedExamRepository.findById(activeId);
        if (activeExamOpt.isEmpty()) {
            session.removeAttribute(ACTIVE_EXAM_SESSION_KEY);
            return null;
        }

        DistributedExam activeExam = activeExamOpt.get();
        if (!sameText(activeExam.getStudentEmail(), studentEmail)
            || activeExam.isSubmitted()
            || isMissedExam(activeExam)) {
            session.removeAttribute(ACTIVE_EXAM_SESSION_KEY);
            return null;
        }

        return "redirect:/student/take-exam/" + activeExam.getId();
    }

    private void clearActiveExamSession(HttpSession session, Long distributedExamId) {
        if (session == null) {
            return;
        }

        Object rawActiveId = session.getAttribute(ACTIVE_EXAM_SESSION_KEY);
        if (rawActiveId == null) {
            return;
        }

        Long activeId = parseLong(String.valueOf(rawActiveId));
        if (activeId == null || distributedExamId == null || distributedExamId.equals(activeId)) {
            session.removeAttribute(ACTIVE_EXAM_SESSION_KEY);
        }
    }

    private boolean sameText(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void classifyMetric(String metricName, double value, List<String> strengths, List<String> weaknesses) {
        if (value >= 70.0) {
            strengths.add(metricName);
        } else {
            weaknesses.add(metricName);
        }
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

        List<Map<String, Object>> parsed = new ArrayList<>();
        String[] rows = answerDetailsJson.split(";");
        for (String row : rows) {
            if (row == null || row.isBlank()) {
                continue;
            }
            String[] parts = row.split("\\|");
            if (parts.length < 4) {
                continue;
            }
            Map<String, Object> detail = new HashMap<>();
            detail.put("questionNumber", parseLong(parts[0]) == null ? 0 : parseLong(parts[0]));
            detail.put("studentAnswer", parts.length > 1 ? parts[1] : "");
            detail.put("correctAnswer", parts.length > 2 ? parts[2] : "");
            detail.put("isCorrect", parts.length > 3 && Boolean.parseBoolean(parts[3]));
            parsed.add(detail);
        }
        return parsed;
    }

    private String csvCell(String value) {
        String text = value == null ? "" : value;
        String escaped = text.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
