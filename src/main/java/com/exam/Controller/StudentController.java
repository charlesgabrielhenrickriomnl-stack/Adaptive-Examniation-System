package com.exam.Controller;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.HtmlUtils;

import com.exam.entity.DistributedExam;
import com.exam.entity.EnrolledStudent;
import com.exam.entity.ExamSubmission;
import com.exam.entity.OriginalProcessedPaper;
import com.exam.entity.Subject;
import com.exam.entity.User;
import com.exam.repository.DistributedExamRepository;
import com.exam.repository.EnrolledStudentRepository;
import com.exam.repository.ExamSubmissionRepository;
import com.exam.repository.OriginalProcessedPaperRepository;
import com.exam.repository.SubjectRepository;
import com.exam.repository.UserRepository;
import com.exam.service.FisherYatesService;
import com.exam.service.IRT3PLService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/student")
@SuppressWarnings("all")
public class StudentController {
    private static final DateTimeFormatter DEADLINE_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm");
    private static final Pattern QUESTION_PARAM_PATTERN = Pattern.compile("q\\d+");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<\\/?[a-z][\\s\\S]*?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_HTML_TAG_PATTERN = Pattern.compile("&lt;\\/?[a-z][\\s\\S]*?&gt;", Pattern.CASE_INSENSITIVE);
    private static final String CHOICE_LABEL_REGEX = "[A-Z]{1,3}|\\d{1,4}";
    private static final String INLINE_DOT_CHOICE_LABEL_REGEX = "[A-Z]|\\d{1,4}";
    private static final Pattern CHOICE_LINE_PATTERN = Pattern.compile("^\\s*(?:\\(?(" + CHOICE_LABEL_REGEX + ")\\)|(" + CHOICE_LABEL_REGEX + ")[.)])\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHOICE_MARKER_PATTERN = Pattern.compile(
        "(?<!\\S)(?:\\(\\s*(" + CHOICE_LABEL_REGEX + ")\\s*\\)|("
            + CHOICE_LABEL_REGEX
            + ")\\)|("
            + INLINE_DOT_CHOICE_LABEL_REGEX
            + ")\\.)(?=\\s+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_LABEL_TOKEN_PATTERN = Pattern.compile("^\\(?\\s*([A-Z]{1,3})\\s*\\)?[.)-]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER_NUMERIC_TOKEN_PATTERN = Pattern.compile("^\\(?\\s*(\\d{1,4})\\s*\\)?[.)-]?$", Pattern.CASE_INSENSITIVE);
    private static final String ACTIVE_EXAM_SESSION_KEY = "ACTIVE_DISTRIBUTED_EXAM_ID";

    private final ExamSubmissionRepository examSubmissionRepository;
    private final EnrolledStudentRepository enrolledStudentRepository;
    private final SubjectRepository subjectRepository;
    private final DistributedExamRepository distributedExamRepository;
    private final OriginalProcessedPaperRepository originalProcessedPaperRepository;
    private final UserRepository userRepository;
    private final FisherYatesService fisherYatesService;
    private final IRT3PLService irt3PLService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Gson gson = new Gson();

    public StudentController(ExamSubmissionRepository examSubmissionRepository,
                             EnrolledStudentRepository enrolledStudentRepository,
                             SubjectRepository subjectRepository,
                             DistributedExamRepository distributedExamRepository,
                             OriginalProcessedPaperRepository originalProcessedPaperRepository,
                             UserRepository userRepository,
                             FisherYatesService fisherYatesService,
                             IRT3PLService irt3PLService,
                             BCryptPasswordEncoder passwordEncoder) {
        this.examSubmissionRepository = examSubmissionRepository;
        this.enrolledStudentRepository = enrolledStudentRepository;
        this.subjectRepository = subjectRepository;
        this.distributedExamRepository = distributedExamRepository;
        this.originalProcessedPaperRepository = originalProcessedPaperRepository;
        this.userRepository = userRepository;
        this.fisherYatesService = fisherYatesService;
        this.irt3PLService = irt3PLService;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/loading")
    public String loading() {
        return "student-loading";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, Principal principal, HttpSession session) {
        String studentEmail = getStudentEmail(principal);
        String activeExamRedirect = redirectToActiveExamIfAny(studentEmail, session);
        if (activeExamRedirect != null) {
            return activeExamRedirect;
        }

        List<EnrolledStudent> enrollments = enrolledStudentRepository.findByStudentEmail(studentEmail);

        List<ExamSubmission> allSubmissions = examSubmissionRepository.findByStudentEmail(studentEmail).stream()
            .sorted(Comparator.comparing(ExamSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .collect(Collectors.toList());

        List<Map<String, Object>> subjectCards = buildSubjectCards(enrollments, studentEmail, allSubmissions);

        model.addAttribute("studentEmail", studentEmail);
        model.addAttribute("hasSubjects", !subjectCards.isEmpty());
        model.addAttribute("subjectCards", subjectCards);
        model.addAttribute("fullName", studentEmail);
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
        return "redirect:/student/dashboard";
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
        String teacherName = resolveTeacherDisplayName(teacherEmail);

        List<ExamSubmission> submissionsBySubject = examSubmissionRepository.findByStudentEmail(studentEmail).stream()
            .filter(sub -> sameText(sub.getSubject(), subjectName))
            .sorted(Comparator.comparing(ExamSubmission::getSubmittedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();

        List<Map<String, Object>> pendingExams = dedupePendingDistributionsByBatch(
                distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
                    .filter(item -> sameText(item.getSubject(), subjectName))
                    .filter(item -> !isMissedExam(item))
                    .toList())
            .stream()
            .map(this::toPendingExamCard)
            .toList();

        model.addAttribute("subjectName", subjectName);
        model.addAttribute("subjectDescription", subjectDescription == null ? "" : subjectDescription);
        model.addAttribute("teacherName", teacherName);
        model.addAttribute("subjectId", subjectId);
        model.addAttribute("pendingExams", pendingExams);
        model.addAttribute("hasHistory", !submissionsBySubject.isEmpty());
        model.addAttribute("subjectSubmissions", submissionsBySubject);
        model.addAttribute("submissionCount", submissionsBySubject.size());
        return "student-classroom";
    }

    @GetMapping("/take-exam")
    public String takeExamFirstAvailable(Principal principal, HttpSession session) {
        String studentEmail = getStudentEmail(principal);
        Optional<DistributedExam> firstPending = dedupePendingDistributionsByBatch(
                distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
                    .filter(item -> !isMissedExam(item))
                    .toList())
            .stream()
            .findFirst();
        if (firstPending.isEmpty()) {
            return "redirect:/student/dashboard";
        }
        session.setAttribute(ACTIVE_EXAM_SESSION_KEY, firstPending.get().getId());
        return "redirect:/student/take-exam/" + firstPending.get().getId();
    }

    @GetMapping("/take-exam/{distributedExamId}")
    public String takeExam(@PathVariable("distributedExamId") Long distributedExamId, Model model, Principal principal, HttpSession session) {
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
                .map(sub -> {
                    Long classroomId = enrolledStudentRepository.findByStudentEmail(studentEmail).stream()
                        .filter(item -> item.getSubjectId() != null)
                        .filter(item -> sameText(item.getSubjectName(), distributedExam.getSubject()))
                        .map(EnrolledStudent::getSubjectId)
                        .findFirst()
                        .orElse(null);

                    String target = "/student/results/" + sub.getId();
                    if (classroomId != null) {
                        target += "?classroomId=" + classroomId;
                    }
                    return "redirect:" + target;
                })
                .orElse("redirect:/student/dashboard");
        }

        if (!isOtpVerified(distributedExam)) {
            populateOtpEntryModel(model, distributedExam, studentEmail);
            return "student-exam-otp";
        }

        ExamContentSnapshot examContent = loadExamContent(distributedExam);
        if (examContent == null) {
            return "redirect:/student/dashboard";
        }

        session.setAttribute(ACTIVE_EXAM_SESSION_KEY, distributedExam.getId());

        List<Map<String, Object>> questionRows = examContent.questionRows();
        Map<String, String> difficultyMap = examContent.difficultyMap();
        Map<String, String> answerKeyMap = examContent.answerKeyMap();
    List<Integer> selectedIndexes = resolveDistributedQuestionIndexes(distributedExam, questionRows.size());
    OriginalProcessedPaper paper = (distributedExam.getExamId() == null || distributedExam.getExamId().isBlank())
        ? null
        : originalProcessedPaperRepository.findByExamId(distributedExam.getExamId()).orElse(null);

        boolean itemMetadataChanged = ensureItemParametersInPlace(questionRows, difficultyMap, selectedIndexes);
        if (itemMetadataChanged && paper != null) {
            paper.setOriginalQuestionsJson(gson.toJson(questionRows));
            originalProcessedPaperRepository.save(paper);
        }

        List<Map<String, Object>> examQuestions = new ArrayList<>();
        List<String> difficulties = new ArrayList<>();
        List<IRT3PLService.ItemParameters> objectiveParams = new ArrayList<>();
        List<Integer> objectiveItemLocals = new ArrayList<>();

        for (Integer sourceIndex : selectedIndexes) {
            if (sourceIndex == null || sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                continue;
            }

            String key = String.valueOf(sourceIndex + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String answer = answerKeyMap.getOrDefault(key, "");
            String displayQuestion = buildQuestionForDelivery(questionRows.get(sourceIndex), difficulty, answer, true);
            if (displayQuestion.isBlank()) {
                continue;
            }

            IRT3PLService.ItemParameters itemParams = resolveItemParameters(questionRows.get(sourceIndex), difficulty);
            List<String> availableChoices = extractChoiceTexts(questionRows.get(sourceIndex), displayQuestion);
            boolean openEnded = isOpenEndedQuestion(questionRows.get(sourceIndex), displayQuestion, answer, availableChoices);

            Map<String, Object> questionItem = new HashMap<>();
            questionItem.put("sourceQuestionNumber", sourceIndex + 1);
            questionItem.put("question", displayQuestion);
            questionItem.put("difficulty", difficulty.isBlank() ? "Medium" : difficulty);
            questionItem.put("questionType", openEnded ? "OPEN_ENDED" : "MULTIPLE_CHOICE");
            questionItem.put("irtA", itemParams.getDiscrimination());
            questionItem.put("irtB", itemParams.getDifficulty());
            questionItem.put("irtC", itemParams.getGuessing());

            if (!openEnded) {
                objectiveParams.add(itemParams);
                objectiveItemLocals.add(examQuestions.size());
            }

            examQuestions.add(questionItem);
            difficulties.add(difficulty.isBlank() ? "Medium" : difficulty);
        }

        if (examQuestions.isEmpty()) {
            clearActiveExamSession(session, distributedExam.getId());
            return "redirect:/student/dashboard";
        }

        int firstQuestionLocal = 0;
        if (!objectiveParams.isEmpty()) {
            int bestObjectiveLocal = irt3PLService.selectNextItem(0.0, objectiveParams, new HashSet<>());
            if (bestObjectiveLocal >= 0 && bestObjectiveLocal < objectiveItemLocals.size()) {
                firstQuestionLocal = objectiveItemLocals.get(bestObjectiveLocal);
            }
        }

        Integer firstSourceNumberRaw = extractInteger(examQuestions.get(firstQuestionLocal).get("sourceQuestionNumber"));
        int firstSourceQuestionNumber = (firstSourceNumberRaw == null || firstSourceNumberRaw <= 0)
            ? 1
            : firstSourceNumberRaw;

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
        examInfo.put("adaptiveEnabled", true);
        examInfo.put("adaptiveFirstSourceQuestionNumber", firstSourceQuestionNumber);

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

    @PostMapping("/take-exam/{distributedExamId}/verify-otp")
    public String verifyExamOtp(@PathVariable("distributedExamId") Long distributedExamId,
                                @RequestParam(name = "otpCode", required = false) String otpCode,
                                Principal principal,
                                RedirectAttributes redirectAttributes) {
        String studentEmail = getStudentEmail(principal);
        DistributedExam distributedExam = distributedExamRepository.findById(distributedExamId).orElse(null);
        if (distributedExam == null || !sameText(distributedExam.getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        if (distributedExam.isSubmitted() || isMissedExam(distributedExam)) {
            return "redirect:/student/dashboard";
        }

        if (isOtpVerified(distributedExam)) {
            return "redirect:/student/take-exam/" + distributedExamId;
        }

        String submittedCode = otpCode == null ? "" : otpCode.trim();
        if (submittedCode.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Enter the OTP given by your teacher.");
            return "redirect:/student/take-exam/" + distributedExamId;
        }

        String otpHash = distributedExam.getAccessOtpHash() == null ? "" : distributedExam.getAccessOtpHash().trim();
        if (otpHash.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "No OTP is available yet. Ask your teacher to generate one.");
            return "redirect:/student/take-exam/" + distributedExamId;
        }

        LocalDateTime expiresAt = distributedExam.getAccessOtpExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) {
            distributedExam.setAccessOtpHash(null);
            distributedExam.setAccessOtpGeneratedAt(null);
            distributedExam.setAccessOtpExpiresAt(null);
            distributedExamRepository.save(distributedExam);
            redirectAttributes.addFlashAttribute("errorMessage", "OTP expired. Ask your teacher for a new code.");
            return "redirect:/student/take-exam/" + distributedExamId;
        }

        if (!passwordEncoder.matches(submittedCode, otpHash)) {
            List<DistributedExam> siblingRows = distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
                .filter(item -> item != null)
                .filter(item -> item.getId() != null && !item.getId().equals(distributedExamId))
                .filter(item -> sameDistributionBatch(item, distributedExam))
                .toList();

            Optional<DistributedExam> matchedSibling = siblingRows.stream()
                .filter(item -> {
                    String siblingHash = item.getAccessOtpHash() == null ? "" : item.getAccessOtpHash().trim();
                    LocalDateTime siblingExpiry = item.getAccessOtpExpiresAt();
                    return !siblingHash.isBlank()
                        && siblingExpiry != null
                        && siblingExpiry.isAfter(LocalDateTime.now())
                        && passwordEncoder.matches(submittedCode, siblingHash);
                })
                .findFirst();

            if (matchedSibling.isPresent()) {
                List<DistributedExam> toVerify = new ArrayList<>(siblingRows);
                toVerify.add(distributedExam);
                LocalDateTime verifiedAt = LocalDateTime.now();

                for (DistributedExam row : toVerify) {
                    row.setAccessOtpVerifiedAt(verifiedAt);
                    row.setAccessOtpHash(null);
                    row.setAccessOtpGeneratedAt(null);
                    row.setAccessOtpExpiresAt(null);
                }
                distributedExamRepository.saveAll(toVerify);
                return "redirect:/student/take-exam/" + distributedExamId;
            }

            redirectAttributes.addFlashAttribute("errorMessage", "Invalid OTP. Please ask your teacher and try again.");
            return "redirect:/student/take-exam/" + distributedExamId;
        }

        distributedExam.setAccessOtpVerifiedAt(LocalDateTime.now());
        distributedExam.setAccessOtpHash(null);
        distributedExam.setAccessOtpGeneratedAt(null);
        distributedExam.setAccessOtpExpiresAt(null);
        distributedExamRepository.save(distributedExam);

        return "redirect:/student/take-exam/" + distributedExamId;
    }

    @PostMapping("/adaptive-next")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> adaptiveNext(@RequestParam Map<String, String> formData,
                                                            Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Long distributedExamId = parseLong(formData.get("distributedExamId"));

        if (distributedExamId == null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Missing distributed exam id."));
        }

        DistributedExam distributedExam = distributedExamRepository.findById(distributedExamId).orElse(null);
        if (distributedExam == null || !sameText(distributedExam.getStudentEmail(), studentEmail)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "message", "Exam access denied."));
        }

        if (!isOtpVerified(distributedExam)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "message", "OTP verification is required before starting."));
        }

        if (distributedExam.isSubmitted() || isMissedExam(distributedExam)) {
            return ResponseEntity.ok(Map.of("ok", true, "done", true, "message", "Exam is no longer active."));
        }

        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(distributedExam.getExamId());
        if (paperOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("ok", true, "done", true, "message", "Question bank unavailable."));
        }

        OriginalProcessedPaper paper = paperOpt.get();
        List<Map<String, Object>> questionRows = parseQuestionsJson(paper.getOriginalQuestionsJson());
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());
        Map<String, String> difficultyMap = parseSimpleMapJson(paper.getDifficultiesJson());
        List<Integer> selectedIndexes = resolveDistributedQuestionIndexes(distributedExam, questionRows.size());

        List<Integer> askedSourceNumbers = parseAdaptiveAskedSourceNumbers(formData.get("askedOrder"));
        Map<Integer, String> answersByDisplay = parseAnswersByDisplayNumber(formData);

        Set<Integer> allowedSourceIndexes = new LinkedHashSet<>(selectedIndexes);
        List<Integer> sanitizedAskedIndexes = new ArrayList<>();
        Set<Integer> seenAsked = new HashSet<>();
        for (Integer sourceNumber : askedSourceNumbers) {
            if (sourceNumber == null || sourceNumber <= 0) {
                continue;
            }
            int sourceIndex = sourceNumber - 1;
            if (!allowedSourceIndexes.contains(sourceIndex) || !seenAsked.add(sourceIndex)) {
                continue;
            }
            sanitizedAskedIndexes.add(sourceIndex);
        }

        List<Boolean> objectiveResponses = new ArrayList<>();
        List<IRT3PLService.ItemParameters> objectiveItemParams = new ArrayList<>();
        for (int displayPosition = 0; displayPosition < sanitizedAskedIndexes.size(); displayPosition++) {
            int sourceIndex = sanitizedAskedIndexes.get(displayPosition);
            if (sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                continue;
            }

            String key = String.valueOf(sourceIndex + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String correctAnswerRaw = answerKeyMap.getOrDefault(key, "");
            String displayQuestion = buildQuestionForDelivery(questionRows.get(sourceIndex), difficulty, correctAnswerRaw, false);
            List<String> availableChoices = extractChoiceTexts(questionRows.get(sourceIndex), displayQuestion);
            if (displayQuestion.isBlank()
                || isOpenEndedQuestion(questionRows.get(sourceIndex), displayQuestion, correctAnswerRaw, availableChoices)) {
                continue;
            }

            String studentAnswer = answersByDisplay.getOrDefault(displayPosition + 1, "").trim();
            if (studentAnswer.isBlank()) {
                continue;
            }

            String resolvedCorrectAnswer = resolveCorrectAnswerText(correctAnswerRaw, availableChoices);
            boolean isCorrect = normalizeAnswer(studentAnswer).equals(normalizeAnswer(resolvedCorrectAnswer));

            objectiveResponses.add(isCorrect);
            objectiveItemParams.add(resolveItemParameters(questionRows.get(sourceIndex), difficulty));
        }

        IRT3PLService.AbilityEstimate abilityEstimate = objectiveResponses.isEmpty()
            ? new IRT3PLService.AbilityEstimate(0.0, 999.0, 0, 0)
            : irt3PLService.estimateAbility(objectiveResponses, objectiveItemParams);

        List<IRT3PLService.ItemParameters> candidateParams = new ArrayList<>();
        Set<Integer> usedLocalIndices = new HashSet<>();
        for (int local = 0; local < selectedIndexes.size(); local++) {
            int sourceIndex = selectedIndexes.get(local);
            if (sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                candidateParams.add(buildDefaultItemParamsForDifficulty("Medium"));
                usedLocalIndices.add(local);
                continue;
            }

            String key = String.valueOf(sourceIndex + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String correctAnswerRaw = answerKeyMap.getOrDefault(key, "");
            String displayQuestion = buildQuestionForDelivery(questionRows.get(sourceIndex), difficulty, correctAnswerRaw, false);
            List<String> availableChoices = extractChoiceTexts(questionRows.get(sourceIndex), displayQuestion);
            if (isOpenEndedQuestion(questionRows.get(sourceIndex), displayQuestion, correctAnswerRaw, availableChoices)) {
                usedLocalIndices.add(local);
            }

            if (sanitizedAskedIndexes.contains(sourceIndex)) {
                usedLocalIndices.add(local);
            }

            candidateParams.add(resolveItemParameters(questionRows.get(sourceIndex), difficulty));
        }

        int nextLocalIndex = irt3PLService.selectNextItem(abilityEstimate.getTheta(), candidateParams, usedLocalIndices);
        if (nextLocalIndex < 0 || nextLocalIndex >= selectedIndexes.size()) {
            // Fallback: continue with the first remaining unasked item (including open-ended)
            // so the student can finish the full distributed exam instead of being forced to submit early.
            Set<Integer> askedSourceIndexSet = new HashSet<>(sanitizedAskedIndexes);
            for (int local = 0; local < selectedIndexes.size(); local++) {
                int sourceIndex = selectedIndexes.get(local);
                if (sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                    continue;
                }
                if (!askedSourceIndexSet.contains(sourceIndex)) {
                    nextLocalIndex = local;
                    break;
                }
            }

            if (nextLocalIndex < 0 || nextLocalIndex >= selectedIndexes.size()) {
                return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "done", true,
                    "theta", abilityEstimate.getTheta(),
                    "standardError", abilityEstimate.getStandardError(),
                    "itemsAnswered", abilityEstimate.getItemsAnswered(),
                    "correctAnswers", abilityEstimate.getCorrectAnswers()
                ));
            }
        }

        int nextSourceQuestionNumber = selectedIndexes.get(nextLocalIndex) + 1;
        Map<String, Object> payload = new HashMap<>();
        payload.put("ok", true);
        payload.put("done", false);
        payload.put("theta", abilityEstimate.getTheta());
        payload.put("standardError", abilityEstimate.getStandardError());
        payload.put("itemsAnswered", abilityEstimate.getItemsAnswered());
        payload.put("correctAnswers", abilityEstimate.getCorrectAnswers());
        payload.put("nextSourceQuestionNumber", nextSourceQuestionNumber);
        return ResponseEntity.ok(payload);
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

        if (!isOtpVerified(distributedExam)) {
            clearActiveExamSession(session, distributedExam.getId());
            return "redirect:/student/take-exam/" + distributedExam.getId();
        }

        ExamContentSnapshot examContent = loadExamContent(distributedExam);
        if (examContent == null) {
            return "redirect:/student/dashboard";
        }

        List<Map<String, Object>> questionRows = examContent.questionRows();
        Map<String, String> answerKeyMap = examContent.answerKeyMap();
        Map<String, String> difficultyMap = examContent.difficultyMap();
    List<Integer> selectedIndexes = resolveDistributedQuestionIndexes(distributedExam, questionRows.size());
    List<Integer> questionOrder = resolveQuestionOrderForSubmission(formData, selectedIndexes);
    OriginalProcessedPaper paper = (distributedExam.getExamId() == null || distributedExam.getExamId().isBlank())
        ? null
        : originalProcessedPaperRepository.findByExamId(distributedExam.getExamId()).orElse(null);

        int totalQuestions = 0;
        int score = 0;
        int objectiveQuestions = 0;
        int objectiveCorrect = 0;
        boolean hasOpenEnded = false;
        List<Map<String, Object>> details = new ArrayList<>();
        List<Map<String, Object>> studentAnswers = new ArrayList<>();
        List<Boolean> objectiveResponses = new ArrayList<>();
        List<IRT3PLService.ItemParameters> objectiveItemParams = new ArrayList<>();

        for (Integer sourceIndex : questionOrder) {
            if (sourceIndex == null || sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                continue;
            }

            String key = String.valueOf(sourceIndex + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String correctAnswerRaw = answerKeyMap.getOrDefault(key, "");
            String displayQuestion = buildQuestionForDelivery(questionRows.get(sourceIndex), difficulty, correctAnswerRaw, false);
            if (displayQuestion.isBlank()) {
                continue;
            }

            List<String> availableChoices = extractChoiceTexts(questionRows.get(sourceIndex), displayQuestion);
            String resolvedCorrectAnswer = resolveCorrectAnswerText(correctAnswerRaw, availableChoices);
            String correctAnswerNormalized = normalizeAnswer(resolvedCorrectAnswer);

            totalQuestions++;
            String answerKey = "q" + totalQuestions;
            String studentAnswer = formData.getOrDefault(answerKey, "").trim();
            boolean openEnded = isOpenEndedQuestion(questionRows.get(sourceIndex), displayQuestion, correctAnswerRaw, availableChoices);
            boolean isCorrect = false;
            String displayCorrectAnswer = resolvedCorrectAnswer;

            if (openEnded) {
                hasOpenEnded = true;
                displayCorrectAnswer = "MANUAL_GRADE";
            } else {
                objectiveQuestions++;
                isCorrect = !studentAnswer.isBlank() && normalizeAnswer(studentAnswer).equals(correctAnswerNormalized);
                if (isCorrect) {
                    score++;
                    objectiveCorrect++;
                }
                objectiveResponses.add(isCorrect);
                objectiveItemParams.add(resolveItemParameters(questionRows.get(sourceIndex), difficulty));
            }

            Map<String, Object> answerItem = new HashMap<>();
            answerItem.put("questionNumber", totalQuestions);
            answerItem.put("sourceQuestionNumber", sourceIndex + 1);
            answerItem.put("question", displayQuestion.replace("[TEXT_INPUT]", "").trim());
            answerItem.put("studentAnswer", studentAnswer.isBlank() ? "No answer" : studentAnswer);
            answerItem.put("correctAnswer", displayCorrectAnswer.isBlank() ? "N/A" : displayCorrectAnswer);
            answerItem.put("isCorrect", isCorrect);
            answerItem.put("difficulty", difficulty.isBlank() ? "Medium" : difficulty);
            answerItem.put("type", openEnded ? "OPEN_ENDED" : "MULTIPLE_CHOICE");
            details.add(answerItem);

            Map<String, Object> submittedAnswer = new HashMap<>();
            submittedAnswer.put("questionNumber", totalQuestions);
            submittedAnswer.put("sourceQuestionNumber", sourceIndex + 1);
            submittedAnswer.put("answer", studentAnswer);
            studentAnswers.add(submittedAnswer);
        }

        if (totalQuestions == 0) {
            return "redirect:/student/dashboard";
        }

        double percentage = (score * 100.0) / totalQuestions;
        double objectivePercentage = objectiveQuestions == 0 ? 0.0 : (objectiveCorrect * 100.0) / objectiveQuestions;

        IRT3PLService.AbilityEstimate abilityEstimate = objectiveResponses.isEmpty()
            ? new IRT3PLService.AbilityEstimate(0.0, 999.0, 0, 0)
            : irt3PLService.estimateAbility(objectiveResponses, objectiveItemParams);
        int irtScaledScore = irt3PLService.thetaToScaledScore(abilityEstimate.getTheta(), 500, 100);
        double thetaNormalizedPercent = clampPercent(((abilityEstimate.getTheta() + 4.0) / 8.0) * 100.0);

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
        submission.setDifficultyResilience(thetaNormalizedPercent);
        submission.setAccuracy(objectivePercentage);
        submission.setTimeEfficiency(Math.min(100.0, Math.max(40.0, 100.0 - (totalQuestions * 1.2))));
        submission.setConfidence(clampPercent(50.0 + (abilityEstimate.getTheta() * 12.5)));
        submission.setPerformanceCategory(performanceCategory(percentage));

        Map<String, Object> payload = new HashMap<>();
        payload.put("submitted", true);
        payload.put("examId", distributedExam.getExamId());
        payload.put("distributedExamId", distributedExam.getId());
        payload.put("deadline", distributedExam.getDeadline());
        payload.put("studentAnswers", studentAnswers);
        payload.put("finalAnswers", details);
        payload.put("adaptiveQuestionOrder", formData.getOrDefault("adaptiveQuestionOrder", ""));
        payload.put("irtTheta", abilityEstimate.getTheta());
        payload.put("irtScaledScore", irtScaledScore);
        payload.put("irtStandardError", abilityEstimate.getStandardError());
        payload.put("irtItemsAnswered", abilityEstimate.getItemsAnswered());
        payload.put("irtCorrectAnswers", abilityEstimate.getCorrectAnswers());
        submission.setAnswerDetailsJson(gson.toJson(payload));

        examSubmissionRepository.save(submission);

        boolean questionBankChanged = incrementExposureCounts(questionRows, questionOrder);
        if (paper != null) {
            boolean difficultyCalibrated = recalibrateQuestionDifficulties(
                paper,
                questionRows,
                distributedExam.getExamId(),
                distributedExam.getExamName(),
                distributedExam.getSubject());

            if (questionBankChanged || difficultyCalibrated) {
                paper.setOriginalQuestionsJson(gson.toJson(questionRows));
                originalProcessedPaperRepository.save(paper);
            }
        }

        distributedExam.setSubmitted(true);
        distributedExamRepository.save(distributedExam);
        clearActiveExamSession(session, distributedExam.getId());

        model.addAttribute("submission", submission);
        return "student-submission-confirmation";
    }

    @GetMapping("/submit")
    public String submitFallbackGet(@RequestParam(name = "distributedExamId", required = false) Long distributedExamId,
                                    Principal principal,
                                    RedirectAttributes redirectAttributes) {
        String studentEmail = getStudentEmail(principal);
        if (distributedExamId != null) {
            Optional<DistributedExam> distributionOpt = distributedExamRepository.findById(distributedExamId);
            if (distributionOpt.isPresent()) {
                DistributedExam distribution = distributionOpt.get();
                if (sameText(distribution.getStudentEmail(), studentEmail) && !distribution.isSubmitted()) {
                    redirectAttributes.addFlashAttribute("errorMessage", "Submission request was interrupted. Please submit your exam again.");
                    return "redirect:/student/take-exam/" + distributedExamId;
                }
            }
        }

        redirectAttributes.addFlashAttribute("errorMessage", "Exam submit page cannot be opened directly.");
        return "redirect:/student/dashboard";
    }

    @GetMapping("/results/{id}")
    public String results(@PathVariable("id") Long id,
                          @RequestParam(value = "classroomId", required = false) Long classroomId,
                          Model model,
                          Principal principal) {
        String studentEmail = getStudentEmail(principal);
        Optional<ExamSubmission> submissionOpt = examSubmissionRepository.findById(id);
        if (submissionOpt.isEmpty() || !sameText(submissionOpt.get().getStudentEmail(), studentEmail)) {
            return "redirect:/student/dashboard";
        }

        ExamSubmission submission = submissionOpt.get();
        List<EnrolledStudent> enrollments = enrolledStudentRepository.findByStudentEmail(studentEmail);

        String backClassroomUrl = null;
        if (classroomId != null) {
            boolean hasAccessToClassroom = enrollments.stream()
                .map(EnrolledStudent::getSubjectId)
                .anyMatch(classroomId::equals);
            if (hasAccessToClassroom) {
                backClassroomUrl = "/student/classroom/" + classroomId;
            }
        }

        if (backClassroomUrl == null) {
            backClassroomUrl = enrollments.stream()
                .filter(enrollment -> enrollment.getSubjectId() != null)
                .filter(enrollment -> sameText(enrollment.getSubjectName(), submission.getSubject()))
                .findFirst()
                .map(enrollment -> "/student/classroom/" + enrollment.getSubjectId())
                .orElse("/student/dashboard");
        }

        boolean resultsReleased = submission.isResultsReleased();
        boolean graded = submission.isGraded();
        boolean resultsLocked = !resultsReleased;

        String releaseStatus;
        String releaseStatusText;
        if (!resultsReleased && !graded) {
            releaseStatus = "PENDING_GRADING";
            releaseStatusText = "Pending teacher grading";
            model.addAttribute("infoMessage", "Your teacher is still reviewing open-ended answers.");
        } else if (!resultsReleased) {
            releaseStatus = "AWAITING_RELEASE";
            releaseStatusText = "Grade finalized, awaiting release";
            model.addAttribute("infoMessage", "Your grade is finalized, but your teacher has not released the final result yet.");
        } else if (graded) {
            releaseStatus = "RELEASED_FINAL";
            releaseStatusText = "Final result released";
        } else {
            releaseStatus = "RELEASED_AUTO";
            releaseStatusText = "Result released";
        }

        Map<String, Object> analytics = new HashMap<>();
        analytics.put("topicMastery", submission.getTopicMastery());
        analytics.put("difficultyResilience", submission.getDifficultyResilience());
        analytics.put("accuracy", submission.getAccuracy());
        analytics.put("timeEfficiency", submission.getTimeEfficiency());
        analytics.put("confidence", submission.getConfidence());
        analytics.put("performanceCategory", submission.getPerformanceCategory());

        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        model.addAttribute("irtTheta", extractDouble(payload.get("irtTheta")));
        model.addAttribute("irtScaledScore", extractInteger(payload.get("irtScaledScore")));
        model.addAttribute("irtStandardError", extractDouble(payload.get("irtStandardError")));

        model.addAttribute("submission", submission);
        model.addAttribute("resultsLocked", resultsLocked);
        model.addAttribute("releaseStatus", releaseStatus);
        model.addAttribute("releaseStatusText", releaseStatusText);
        model.addAttribute("score", submission.getScore());
        model.addAttribute("total", submission.getTotalQuestions());
        model.addAttribute("percentage", submission.getPercentage());
        model.addAttribute("analytics", analytics);
        model.addAttribute("backClassroomUrl", backClassroomUrl);
        return "student-results";
    }

    @GetMapping("/performance-analytics/{id}")
    public String performanceAnalytics(@PathVariable("id") Long id, Model model, Principal principal) {
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
    public String viewExam(@PathVariable("id") Long id, Model model, Principal principal) {
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
    public ResponseEntity<byte[]> downloadResult(@PathVariable("id") Long id, Principal principal) {
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
        List<ExamSubmission> allSubmissions = examSubmissionRepository.findByStudentEmail(studentEmail);
        model.addAttribute("totalAttempts", allSubmissions.size());
        double avgScore = allSubmissions.isEmpty() ? 0.0 : allSubmissions.stream().mapToDouble(ExamSubmission::getPercentage).average().orElse(0.0);
        model.addAttribute("avgScore", String.format("%.1f", avgScore));
        long passedCount = allSubmissions.stream().filter(sub -> sub.getPercentage() >= 60.0).count();
        model.addAttribute("passedCount", passedCount);
        double bestScore = allSubmissions.stream().mapToDouble(ExamSubmission::getPercentage).max().orElse(0.0);
        model.addAttribute("bestScore", String.format("%.1f", bestScore));
        model.addAttribute("studentEmail", studentEmail);
        return "student-profile";
    }

    private String getStudentEmail(Principal principal) {
        return principal != null && principal.getName() != null ? principal.getName() : "student";
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
            String teacherName = resolveTeacherDisplayName(teacherEmail);

            String normalizedName = subjectName == null ? "" : subjectName.trim();
            if (normalizedName.isBlank() || "subject".equalsIgnoreCase(normalizedName)) {
                continue;
            }

            Map<String, Long> pendingByType = new LinkedHashMap<>();
            List<DistributedExam> pendingRows = dedupePendingDistributionsByBatch(
                distributedExamRepository.findByStudentEmailAndSubmittedFalse(studentEmail).stream()
                    .filter(item -> sameText(item.getSubject(), subjectName))
                    .filter(item -> !isMissedExam(item))
                    .toList());

            pendingRows.forEach(item -> {
                String type = (item.getActivityType() == null || item.getActivityType().isBlank())
                    ? "Activity"
                    : item.getActivityType().trim();
                pendingByType.merge(type, 1L, Long::sum);
            });

            List<Map<String, Object>> pendingSummaries = new ArrayList<>();
            long pendingCount = 0L;
            for (Map.Entry<String, Long> entry : pendingByType.entrySet()) {
                String type = entry.getKey();
                long count = entry.getValue() == null ? 0L : entry.getValue();
                if (count <= 0) {
                    continue;
                }
                Map<String, Object> summary = new HashMap<>();
                summary.put("label", "Pending " + type);
                pendingCount += count;
                summary.put("count", count);
                pendingSummaries.add(summary);
            }

            long completedCount = allSubmissions.stream()
                .filter(sub -> sameText(sub.getSubject(), subjectName))
                .count();

            Map<String, Object> card = new HashMap<>();
            card.put("id", enrollment.getSubjectId());
            card.put("name", normalizedName);
            card.put("description", subjectDescription == null ? "" : subjectDescription);
            card.put("teacherName", teacherName);
            card.put("teacherEmail", teacherEmail == null ? "" : teacherEmail);
            card.put("pendingSummaries", pendingSummaries);
            card.put("pendingCount", pendingCount);
            card.put("completedCount", completedCount);
            cards.add(card);
        }
        return cards;
    }

    private Map<String, Object> toPendingExamCard(DistributedExam item) {
        Map<String, Object> card = new HashMap<>();
        boolean otpVerified = isOtpVerified(item);
        card.put("name", item.getExamName());
        card.put("type", item.getActivityType() == null ? "Exam" : item.getActivityType());
        card.put("questionCount", countQuestions(item));
        card.put("timeLimit", item.getTimeLimit() == null ? 60 : item.getTimeLimit());
        card.put("deadline", formatDeadline(item.getDeadline()));
        card.put("startUrl", "/student/take-exam/" + item.getId());
        card.put("otpBadge", otpVerified ? "OTP verified" : "Teacher OTP required");
        card.put("otpBadgeClass", otpVerified ? "bg-success-subtle text-success border" : "bg-warning-subtle text-dark border");
        return card;
    }

    private void populateOtpEntryModel(Model model, DistributedExam distributedExam, String studentEmail) {
        if (model == null || distributedExam == null) {
            return;
        }

        model.addAttribute("distributedExamId", distributedExam.getId());
        model.addAttribute("examName", distributedExam.getExamName());
        model.addAttribute("activityType", distributedExam.getActivityType() == null ? "Exam" : distributedExam.getActivityType());
        model.addAttribute("subjectName", distributedExam.getSubject());
        model.addAttribute("timeLimit", distributedExam.getTimeLimit() == null ? 60 : distributedExam.getTimeLimit());
        model.addAttribute("deadline", formatDeadline(distributedExam.getDeadline()));

        Long classroomId = enrolledStudentRepository.findByStudentEmail(studentEmail).stream()
            .filter(item -> item.getSubjectId() != null)
            .filter(item -> sameText(item.getSubjectName(), distributedExam.getSubject()))
            .map(EnrolledStudent::getSubjectId)
            .findFirst()
            .orElse(null);

        String classroomUrl = classroomId == null ? "/student/dashboard" : "/student/classroom/" + classroomId;
        model.addAttribute("classroomUrl", classroomUrl);

        String hash = distributedExam.getAccessOtpHash() == null ? "" : distributedExam.getAccessOtpHash().trim();
        LocalDateTime expiresAt = distributedExam.getAccessOtpExpiresAt();
        if (hash.isBlank()) {
            model.addAttribute("otpPromptMessage", "Your teacher has not generated an OTP yet. Please ask your teacher in person.");
        } else if (expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) {
            model.addAttribute("otpPromptMessage", "The previous OTP has expired. Please ask your teacher for a new code.");
        } else {
            model.addAttribute("otpExpiresAt", expiresAt.format(DEADLINE_DISPLAY_FORMAT));
        }
    }

    private boolean isOtpVerified(DistributedExam distributedExam) {
        return distributedExam != null && distributedExam.getAccessOtpVerifiedAt() != null;
    }

    private int countQuestions(DistributedExam distributedExam) {
        if (distributedExam == null) {
            return 0;
        }

        String examId = distributedExam.getExamId();
        if (examId == null || examId.isBlank()) {
            return 0;
        }

        // Question items are never stored in DistributedExam; always count from OriginalProcessedPaper.
        return originalProcessedPaperRepository.findByExamId(examId)
            .map(paper -> {
                int totalQuestions = parseQuestionsJson(paper.getOriginalQuestionsJson()).size();
                List<Integer> selectedIndexes = resolveDistributedQuestionIndexes(distributedExam, totalQuestions);
                return selectedIndexes.size();
            })
            .orElse(0);
    }

    private String resolveTeacherDisplayName(String teacherEmail) {
        if (teacherEmail == null || teacherEmail.isBlank()) {
            return "Unknown Teacher";
        }

        String normalizedEmail = teacherEmail.trim();
        Optional<User> teacherOpt = userRepository.findByEmail(normalizedEmail);
        if (teacherOpt.isPresent() && teacherOpt.get().getFullName() != null && !teacherOpt.get().getFullName().isBlank()) {
            return teacherOpt.get().getFullName().trim();
        }

        int atIndex = normalizedEmail.indexOf('@');
        String fallback = atIndex > 0 ? normalizedEmail.substring(0, atIndex) : normalizedEmail;
        fallback = fallback.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        return fallback.isBlank() ? "Unknown Teacher" : fallback;
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

    private Map<String, Object> parseFlexibleMapJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> parsed = gson.fromJson(json,
                new TypeToken<Map<String, Object>>() { }.getType());
            return parsed == null ? new HashMap<>() : parsed;
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }

    private Double extractDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer extractInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Integer> resolveDistributedQuestionIndexes(DistributedExam distributedExam, int totalQuestions) {
        if (totalQuestions <= 0) {
            return new ArrayList<>();
        }

        List<Integer> parsedIndexes = parseIntegerListJson(
            distributedExam == null ? null : distributedExam.getQuestionIndexesJson());

        List<Integer> normalized = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Integer index : parsedIndexes) {
            if (index == null || index < 0 || index >= totalQuestions) {
                continue;
            }
            if (seen.add(index)) {
                normalized.add(index);
            }
        }

        if (!normalized.isEmpty()) {
            return normalized;
        }

        List<Integer> fallback = new ArrayList<>();
        for (int index = 0; index < totalQuestions; index++) {
            fallback.add(index);
        }
        return fallback;
    }

    private List<Integer> parseIntegerListJson(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }

        try {
            List<?> parsed = gson.fromJson(json, new TypeToken<List<?>>() { }.getType());
            List<Integer> result = new ArrayList<>();
            if (parsed == null) {
                return result;
            }

            for (Object item : parsed) {
                if (item instanceof Number number) {
                    result.add(number.intValue());
                    continue;
                }
                if (item == null) {
                    continue;
                }
                Long parsedLong = parseLong(String.valueOf(item));
                if (parsedLong != null) {
                    result.add(parsedLong.intValue());
                }
            }
            return result;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private List<Integer> parseAdaptiveAskedSourceNumbers(String rawAskedOrder) {
        List<Integer> parsed = parseIntegerListJson(rawAskedOrder);
        if (!parsed.isEmpty()) {
            Set<Integer> seen = new LinkedHashSet<>();
            for (Integer value : parsed) {
                if (value != null && value > 0) {
                    seen.add(value);
                }
            }
            return new ArrayList<>(seen);
        }

        if (rawAskedOrder == null || rawAskedOrder.isBlank()) {
            return new ArrayList<>();
        }

        Set<Integer> fallback = new LinkedHashSet<>();
        for (String token : rawAskedOrder.split("[,\\s]+")) {
            Long parsedLong = parseLong(token);
            if (parsedLong != null && parsedLong > 0) {
                fallback.add(parsedLong.intValue());
            }
        }
        return new ArrayList<>(fallback);
    }

    private Map<Integer, String> parseAnswersByDisplayNumber(Map<String, String> formData) {
        Map<Integer, String> answersByDisplay = new HashMap<>();
        if (formData == null || formData.isEmpty()) {
            return answersByDisplay;
        }

        for (Map.Entry<String, String> entry : formData.entrySet()) {
            String key = entry.getKey();
            if (key == null || !QUESTION_PARAM_PATTERN.matcher(key).matches()) {
                continue;
            }

            Long questionNumber = parseLong(key.substring(1));
            if (questionNumber == null || questionNumber <= 0) {
                continue;
            }
            answersByDisplay.put(questionNumber.intValue(), entry.getValue() == null ? "" : entry.getValue());
        }
        return answersByDisplay;
    }

    private List<Integer> resolveQuestionOrderForSubmission(Map<String, String> formData, List<Integer> selectedIndexes) {
        if (selectedIndexes == null || selectedIndexes.isEmpty()) {
            return new ArrayList<>();
        }

        String rawAdaptiveOrder = formData == null ? null : formData.get("adaptiveQuestionOrder");
        List<Integer> adaptiveSourceNumbers = parseAdaptiveAskedSourceNumbers(rawAdaptiveOrder);
        if (adaptiveSourceNumbers.isEmpty()) {
            return new ArrayList<>(selectedIndexes);
        }

        Set<Integer> allowed = new LinkedHashSet<>(selectedIndexes);
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        for (Integer sourceNumber : adaptiveSourceNumbers) {
            if (sourceNumber == null || sourceNumber <= 0) {
                continue;
            }
            int sourceIndex = sourceNumber - 1;
            if (!allowed.contains(sourceIndex) || !seen.add(sourceIndex)) {
                continue;
            }
            ordered.add(sourceIndex);
        }

        for (Integer sourceIndex : selectedIndexes) {
            if (sourceIndex == null || seen.contains(sourceIndex)) {
                continue;
            }
            ordered.add(sourceIndex);
        }

        return ordered;
    }

    private boolean ensureItemParametersInPlace(List<Map<String, Object>> questionRows,
                                                Map<String, String> difficultyMap,
                                                List<Integer> sourceIndexes) {
        if (questionRows == null || questionRows.isEmpty() || sourceIndexes == null || sourceIndexes.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (Integer sourceIndex : sourceIndexes) {
            if (sourceIndex == null || sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                continue;
            }

            Map<String, Object> row = questionRows.get(sourceIndex);
            if (row == null) {
                continue;
            }

            String key = String.valueOf(sourceIndex + 1);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            IRT3PLService.ItemParameters params = resolveItemParameters(row, difficulty);

            if (!matchesNumeric(row.get("irtA"), params.getDiscrimination())) {
                row.put("irtA", params.getDiscrimination());
                changed = true;
            }
            if (!matchesNumeric(row.get("irtB"), params.getDifficulty())) {
                row.put("irtB", params.getDifficulty());
                changed = true;
            }
            if (!matchesNumeric(row.get("irtC"), params.getGuessing())) {
                row.put("irtC", params.getGuessing());
                changed = true;
            }

            if (extractInteger(row.get("exposureCount")) == null) {
                row.put("exposureCount", 0);
                changed = true;
            }
        }

        return changed;
    }

    private boolean incrementExposureCounts(List<Map<String, Object>> questionRows, List<Integer> askedOrder) {
        if (questionRows == null || questionRows.isEmpty() || askedOrder == null || askedOrder.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (Integer sourceIndex : askedOrder) {
            if (sourceIndex == null || sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                continue;
            }

            Map<String, Object> row = questionRows.get(sourceIndex);
            if (row == null) {
                continue;
            }

            Integer currentExposure = extractInteger(row.get("exposureCount"));
            int nextExposure = (currentExposure == null ? 0 : currentExposure) + 1;
            row.put("exposureCount", nextExposure);
            changed = true;
        }

        return changed;
    }

    private boolean recalibrateQuestionDifficulties(OriginalProcessedPaper paper,
                                                    List<Map<String, Object>> questionRows,
                                                    String examId,
                                                    String examName,
                                                    String subject) {
        if (paper == null
            || questionRows == null
            || questionRows.isEmpty()
            || examId == null
            || examId.isBlank()) {
            return false;
        }

        List<ExamSubmission> submissions = examSubmissionRepository.findByExamNameAndSubject(examName, subject);
        if (submissions.isEmpty()) {
            return false;
        }

        Map<Integer, int[]> statsBySourceQuestion = new HashMap<>();
        for (ExamSubmission submission : submissions) {
            if (submission == null || !matchesSubmissionExamId(submission, examId, examName, subject)) {
                continue;
            }

            Set<Integer> countedForSubmission = new HashSet<>();
            List<Map<String, Object>> finalAnswers = extractAnswerDetails(submission.getAnswerDetailsJson());
            for (Map<String, Object> answerItem : finalAnswers) {
                if (answerItem == null) {
                    continue;
                }

                String type = String.valueOf(answerItem.getOrDefault("type", ""));
                if ("OPEN_ENDED".equalsIgnoreCase(type.trim())) {
                    continue;
                }

                Integer sourceQuestionNumber = extractInteger(answerItem.get("sourceQuestionNumber"));
                if (sourceQuestionNumber == null || sourceQuestionNumber <= 0) {
                    sourceQuestionNumber = extractInteger(answerItem.get("questionNumber"));
                }
                if (sourceQuestionNumber == null || sourceQuestionNumber <= 0) {
                    continue;
                }
                if (!countedForSubmission.add(sourceQuestionNumber)) {
                    continue;
                }

                Boolean isCorrect = extractBoolean(answerItem.get("isCorrect"));
                if (isCorrect == null) {
                    continue;
                }

                int[] stats = statsBySourceQuestion.computeIfAbsent(sourceQuestionNumber, key -> new int[2]);
                if (isCorrect) {
                    stats[0]++;
                }
                stats[1]++;
            }
        }

        if (statsBySourceQuestion.isEmpty()) {
            return false;
        }

        Map<String, String> difficultyMap = parseSimpleMapJson(paper.getDifficultiesJson());
        Map<String, String> answerKeyMap = parseSimpleMapJson(paper.getAnswerKeyJson());
        boolean changed = false;

        for (Map.Entry<Integer, int[]> entry : statsBySourceQuestion.entrySet()) {
            Integer sourceQuestionNumber = entry.getKey();
            int[] stats = entry.getValue();
            if (sourceQuestionNumber == null || sourceQuestionNumber <= 0 || stats == null) {
                continue;
            }

            int sourceIndex = sourceQuestionNumber - 1;
            if (sourceIndex < 0 || sourceIndex >= questionRows.size()) {
                continue;
            }

            int total = stats[1];
            if (total < 3) {
                continue;
            }

            Map<String, Object> questionRow = questionRows.get(sourceIndex);
            if (questionRow == null) {
                continue;
            }

            String key = String.valueOf(sourceQuestionNumber);
            String difficulty = normalizeDifficulty(difficultyMap.getOrDefault(key, "Medium"));
            String correctAnswerRaw = answerKeyMap.getOrDefault(key, "");
            String displayQuestion = buildQuestionForDelivery(questionRow, difficulty, correctAnswerRaw, false);
            List<String> availableChoices = extractChoiceTexts(questionRow, displayQuestion);
            if (isOpenEndedQuestion(questionRow, displayQuestion, correctAnswerRaw, availableChoices)) {
                continue;
            }

            IRT3PLService.ItemParameters params = resolveItemParameters(questionRow, difficulty);
            double nextDifficulty = estimateNextDifficulty(params, stats[0], total);
            if (!matchesNumeric(questionRow.get("irtB"), nextDifficulty)) {
                questionRow.put("irtB", nextDifficulty);
                changed = true;
            }
        }

        return changed;
    }

    private boolean matchesSubmissionExamId(ExamSubmission submission,
                                            String examId,
                                            String examName,
                                            String subject) {
        if (submission == null || examId == null || examId.isBlank()) {
            return false;
        }

        Map<String, Object> payload = parseFlexibleMapJson(submission.getAnswerDetailsJson());
        Object payloadExamId = payload.get("examId");
        if (payloadExamId == null || String.valueOf(payloadExamId).isBlank()) {
            return sameText(submission.getExamName(), examName)
                && sameText(submission.getSubject(), subject);
        }

        return sameText(String.valueOf(payloadExamId), examId);
    }

    private double estimateNextDifficulty(IRT3PLService.ItemParameters params, int correct, int total) {
        if (params == null || total <= 0) {
            return 0.0;
        }

        double a = Math.max(0.30, Math.min(3.00, params.getDiscrimination()));
        double c = Math.max(0.00, Math.min(0.35, params.getGuessing()));
        double currentB = Math.max(-3.00, Math.min(3.00, params.getDifficulty()));

        // Laplace smoothing keeps p away from exact 0/1 and stabilizes small cohorts.
        double observedP = (correct + 0.5) / (total + 1.0);
        double minP = Math.min(0.95, c + 0.03);
        double boundedP = clamp(observedP, minP, 0.97);

        double denominator = boundedP - c;
        if (denominator <= 0.0001) {
            return currentB;
        }

        double ratio = ((1.0 - c) / denominator) - 1.0;
        if (ratio <= 0.0001) {
            return currentB;
        }

        double targetB = clamp(Math.log(ratio) / a, -3.00, 3.00);
        double learningRate = clamp(0.10 + (total * 0.02), 0.10, 0.45);
        return clamp(currentB + (learningRate * (targetB - currentB)), -3.00, 3.00);
    }

    private IRT3PLService.ItemParameters resolveItemParameters(Map<String, Object> questionRow, String difficulty) {
        IRT3PLService.ItemParameters defaults = buildDefaultItemParamsForDifficulty(difficulty);
        if (questionRow == null || questionRow.isEmpty()) {
            return defaults;
        }

        Double discrimination = firstNonNullDouble(
            extractDouble(questionRow.get("irtA")),
            extractDouble(questionRow.get("a")),
            extractDouble(questionRow.get("discrimination")),
            defaults.getDiscrimination());

        Double itemDifficulty = firstNonNullDouble(
            extractDouble(questionRow.get("irtB")),
            extractDouble(questionRow.get("b")),
            extractDouble(questionRow.get("difficultyParam")),
            defaults.getDifficulty());

        Double guessing = firstNonNullDouble(
            extractDouble(questionRow.get("irtC")),
            extractDouble(questionRow.get("c")),
            extractDouble(questionRow.get("guessing")),
            defaults.getGuessing());

        double clampedA = Math.max(0.30, Math.min(3.00, discrimination == null ? defaults.getDiscrimination() : discrimination));
        double clampedB = Math.max(-3.00, Math.min(3.00, itemDifficulty == null ? defaults.getDifficulty() : itemDifficulty));
        double clampedC = Math.max(0.00, Math.min(0.35, guessing == null ? defaults.getGuessing() : guessing));
        return new IRT3PLService.ItemParameters(clampedA, clampedB, clampedC);
    }

    private Double firstNonNullDouble(Double... values) {
        if (values == null) {
            return null;
        }
        for (Double value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private boolean matchesNumeric(Object rawValue, double expected) {
        Double parsed = extractDouble(rawValue);
        return parsed != null && Math.abs(parsed - expected) < 0.00001;
    }

    private Boolean extractBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        if (value == null) {
            return null;
        }

        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
            return false;
        }
        return null;
    }

    private boolean isOpenEndedQuestion(Map<String, Object> questionRow,
                                        String displayQuestion,
                                        String correctAnswerRaw,
                                        List<String> availableChoices) {
        if (displayQuestion != null && displayQuestion.startsWith("[TEXT_INPUT]")) {
            return true;
        }

        String normalizedAnswer = normalizeAnswer(correctAnswerRaw);
        if ("manual_grade".equals(normalizedAnswer)) {
            return true;
        }

        if (questionRow != null) {
            Object choicesObj = questionRow.get("choices");
            if (choicesObj instanceof List<?> list && list.isEmpty()) {
                return true;
            }

            Object questionTypeRaw = questionRow.get("questionType");
            if (questionTypeRaw != null && "OPEN_ENDED".equalsIgnoreCase(String.valueOf(questionTypeRaw).trim())) {
                return true;
            }
        }

        return availableChoices == null || availableChoices.size() < 2;
    }

    private String buildQuestionForDelivery(Map<String, Object> questionRow,
                                            String difficulty,
                                            String answer,
                                            boolean shuffleChoices) {
        String resolved = resolveQuestionDisplay(questionRow, difficulty, answer);
        if (resolved.isBlank()) {
            return "";
        }

        boolean openEnded = resolved.startsWith("[TEXT_INPUT]");
        String body = openEnded ? resolved.substring("[TEXT_INPUT]".length()).trim() : resolved;
        if (body.isBlank()) {
            return openEnded ? "[TEXT_INPUT]" : "";
        }

        if (openEnded) {
            return "[TEXT_INPUT]" + body;
        }

        List<String> extractedChoices = extractChoiceTexts(questionRow, body);
        if (extractedChoices.size() < 2) {
            return body;
        }

        List<String> choices = new ArrayList<>(extractedChoices);
        if (shuffleChoices) {
            fisherYatesService.shuffle(choices);
        }

        String stem = extractQuestionStem(body);
        if (stem.isBlank()) {
            stem = body;
        }

        StringBuilder builder = new StringBuilder(stem);
        for (int index = 0; index < choices.size(); index++) {
            String choiceLabel = generateChoiceLabel(index);
            builder.append("\n").append(choiceLabel).append(") ").append(choices.get(index));
        }
        return builder.toString();
    }

    private List<String> extractChoiceTexts(Map<String, Object> questionRow, String questionText) {
        List<String> fromRow = extractChoicesFromRow(questionRow);
        if (fromRow.size() >= 2) {
            return fromRow;
        }

        String plain = toPlainQuestionText(questionText);
        if (plain.isBlank()) {
            return new ArrayList<>();
        }

        List<String> lines = new ArrayList<>();
        for (String line : plain.split("\\n+")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                lines.add(trimmed);
            }
        }

        List<String> parsed = new ArrayList<>();
        if (lines.size() > 1) {
            for (int index = 1; index < lines.size(); index++) {
                java.util.regex.Matcher lineMatcher = CHOICE_LINE_PATTERN.matcher(lines.get(index));
                if (!lineMatcher.matches()) {
                    continue;
                }
                String choice = normalizeQuestionHtml(lineMatcher.group(3));
                if (!choice.isBlank()) {
                    parsed.add(choice);
                }
            }
        }

        if (parsed.size() >= 2) {
            return parsed;
        }

        return extractInlineChoices(plain);
    }

    private List<String> extractChoicesFromRow(Map<String, Object> questionRow) {
        Object choicesObj = questionRow == null ? null : questionRow.get("choices");
        if (!(choicesObj instanceof List<?> list)) {
            return new ArrayList<>();
        }

        List<String> choices = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String normalized = normalizeQuestionHtml(String.valueOf(item));
            if (!normalized.isBlank()) {
                choices.add(normalized);
            }
        }
        return choices;
    }

    private List<String> extractInlineChoices(String plainQuestionText) {
        List<String> choices = new ArrayList<>();
        java.util.regex.Matcher markerMatcher = CHOICE_MARKER_PATTERN.matcher(plainQuestionText);
        List<Integer> markerStarts = new ArrayList<>();
        List<Integer> markerEnds = new ArrayList<>();

        while (markerMatcher.find()) {
            markerStarts.add(markerMatcher.start());
            markerEnds.add(markerMatcher.end());
        }

        if (markerStarts.size() < 2) {
            return choices;
        }

        for (int index = 0; index < markerStarts.size(); index++) {
            int start = markerEnds.get(index);
            int end = (index + 1 < markerStarts.size()) ? markerStarts.get(index + 1) : plainQuestionText.length();
            if (start >= end) {
                continue;
            }
            String choice = normalizeQuestionHtml(plainQuestionText.substring(start, end).replaceAll("\\s+", " ").trim());
            if (!choice.isBlank()) {
                choices.add(choice);
            }
        }

        return choices;
    }

    private String extractQuestionStem(String questionText) {
        String plain = toPlainQuestionText(questionText);
        if (plain.isBlank()) {
            return "";
        }

        List<String> lines = new ArrayList<>();
        for (String line : plain.split("\\n+")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank()) {
                lines.add(trimmed);
            }
        }

        if (lines.size() > 1) {
            java.util.regex.Matcher firstChoiceMatcher = CHOICE_LINE_PATTERN.matcher(lines.get(1));
            if (firstChoiceMatcher.matches()) {
                return lines.get(0);
            }
        }

        java.util.regex.Matcher inlineMatcher = CHOICE_MARKER_PATTERN.matcher(plain);
        if (inlineMatcher.find() && inlineMatcher.start() > 0) {
            String stem = plain.substring(0, inlineMatcher.start()).trim();
            if (!stem.isBlank()) {
                return stem;
            }
        }

        return lines.isEmpty() ? plain : lines.get(0);
    }

    private String resolveCorrectAnswerText(String rawCorrectAnswer, List<String> choices) {
        String cleaned = normalizeQuestionHtml(rawCorrectAnswer);
        if (cleaned.isBlank()) {
            return "";
        }

        Integer parsedIndex = parseAnswerChoiceIndex(cleaned, choices == null ? 0 : choices.size());
        if (parsedIndex != null && choices != null && parsedIndex >= 0 && parsedIndex < choices.size()) {
            String mapped = normalizeQuestionHtml(choices.get(parsedIndex));
            if (!mapped.isBlank()) {
                return mapped;
            }
        }

        return cleaned;
    }

    private Integer parseAnswerChoiceIndex(String rawAnswer, int choiceCount) {
        if (rawAnswer == null || rawAnswer.isBlank() || choiceCount <= 0) {
            return null;
        }

        String candidate = rawAnswer.trim().toUpperCase(Locale.ROOT);
        java.util.regex.Matcher numericMatcher = ANSWER_NUMERIC_TOKEN_PATTERN.matcher(candidate);
        if (numericMatcher.matches()) {
            try {
                int parsed = Integer.parseInt(numericMatcher.group(1));
                int index = parsed - 1;
                if (index >= 0 && index < choiceCount) {
                    return index;
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed numeric answer tokens and continue label parsing.
            }
        }

        java.util.regex.Matcher labelMatcher = ANSWER_LABEL_TOKEN_PATTERN.matcher(candidate);
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

        String upper = rawLabel.trim().toUpperCase(Locale.ROOT);
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

    private String toPlainQuestionText(String value) {
        return String.valueOf(value == null ? "" : value)
            .replaceAll("(?i)<br\\s*/?>", "\\n")
            .replaceAll("(?i)<sup[^>]*>\\s*(.*?)\\s*</sup>", "^$1")
            .replaceAll("(?i)<sub[^>]*>\\s*(.*?)\\s*</sub>", "_$1")
            .replaceAll("(?i)</(p|div|li|tr|h[1-6])>", "\\n")
            .replaceAll("(?i)<li[^>]*>", "")
            .replaceAll("<[^>]+>", " ")
            .replace('\u00A0', ' ')
            .replace("\r", "\n")
            .replaceAll("[ \\t]+\\n", "\\n")
            .replaceAll("\\n{2,}", "\\n")
            .replaceAll("[ \\t]{2,}", " ")
            .trim();
    }

    private IRT3PLService.ItemParameters buildDefaultItemParamsForDifficulty(String difficulty) {
        String normalized = normalizeDifficulty(difficulty);
        if ("Easy".equalsIgnoreCase(normalized)) {
            return new IRT3PLService.ItemParameters(1.00, -0.80, 0.20);
        }
        if ("Hard".equalsIgnoreCase(normalized)) {
            return new IRT3PLService.ItemParameters(1.40, 0.80, 0.20);
        }
        return new IRT3PLService.ItemParameters(1.20, 0.00, 0.20);
    }

    private double clampPercent(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private ExamContentSnapshot loadExamContent(DistributedExam distributedExam) {
        if (distributedExam == null) {
            return null;
        }

        String examId = distributedExam.getExamId();
        if (examId == null || examId.isBlank()) {
            return null;
        }

        // Question items are never stored in DistributedExam; always load from OriginalProcessedPaper.
        Optional<OriginalProcessedPaper> paperOpt = originalProcessedPaperRepository.findByExamId(examId);

        if (paperOpt.isEmpty()) {
            return null;
        }

        OriginalProcessedPaper paper = paperOpt.get();
        return new ExamContentSnapshot(
            parseQuestionsJson(paper.getOriginalQuestionsJson()),
            parseSimpleMapJson(paper.getDifficultiesJson()),
            parseSimpleMapJson(paper.getAnswerKeyJson())
        );
    }

    private record ExamContentSnapshot(List<Map<String, Object>> questionRows,
                                       Map<String, String> difficultyMap,
                                       Map<String, String> answerKeyMap) {
    }

    private String resolveQuestionDisplay(Map<String, Object> questionRow, String difficulty, String answer) {
        String rawText = normalizeQuestionHtml(String.valueOf(questionRow.getOrDefault("question", "")));
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
                    String normalized = normalizeQuestionHtml(String.valueOf(choice));
                    if (!normalized.isBlank()) {
                        choices.add(normalized);
                    }
                }
            }
        }

        String stem = normalizeQuestionHtml(String.valueOf(questionRow.getOrDefault("question_text", questionRow.getOrDefault("text", ""))));
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
        for (int index = 0; index < choices.size(); index++) {
            String choiceLabel = generateChoiceLabel(index);
            builder.append("\n").append(choiceLabel).append(") ").append(choices.get(index));
        }
        return builder.toString();
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

    private String stripClipboardFragmentMarkers(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
            .replaceAll("(?i)<!--\\s*(StartFragment|EndFragment)\\s*-->", "")
            .replaceAll("(?i)&lt;!--\\s*(StartFragment|EndFragment)\\s*--&gt;", "")
            .trim();
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
        if (rawActiveId != null) {
            Long activeId = parseLong(String.valueOf(rawActiveId));
            if (activeId == null || distributedExamId == null || distributedExamId.equals(activeId)) {
                session.removeAttribute(ACTIVE_EXAM_SESSION_KEY);
            }
        }
    }

    private boolean sameText(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String buildDistributionBatchKey(DistributedExam item) {
        if (item == null) {
            return "";
        }

        String examIdentity = normalizeText(item.getExamId()).isBlank()
            ? "name:" + normalizeText(item.getExamName())
            : "id:" + normalizeText(item.getExamId());

        return String.join("|",
            normalizeText(item.getSubject()),
            examIdentity,
            normalizeText(item.getActivityType()),
            String.valueOf(item.getTimeLimit() == null ? 60 : item.getTimeLimit()),
            normalizeText(item.getDeadline()));
    }

    private boolean sameDistributionBatch(DistributedExam left, DistributedExam right) {
        String leftKey = buildDistributionBatchKey(left);
        String rightKey = buildDistributionBatchKey(right);
        return !leftKey.isBlank() && leftKey.equals(rightKey);
    }

    private List<DistributedExam> dedupePendingDistributionsByBatch(List<DistributedExam> rows) {
        List<DistributedExam> sorted = new ArrayList<>(rows == null ? new ArrayList<>() : rows);
        sorted.sort(Comparator.comparing(DistributedExam::getDistributedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));

        Map<String, DistributedExam> latestByBatch = new LinkedHashMap<>();
        for (DistributedExam row : sorted) {
            if (row == null) {
                continue;
            }

            String batchKey = buildDistributionBatchKey(row);
            if (batchKey.isBlank()) {
                continue;
            }

            latestByBatch.putIfAbsent(batchKey, row);
        }

        return new ArrayList<>(latestByBatch.values());
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
