/**
 * Student Exam Page JavaScript
 * Handles exam navigation, timer, auto-save, and anti-cheating features
 */

// Global variables (will be initialized from Thymeleaf)
let exam = [];
let difficulties = [];
let examInfo = {};
let examItems = [];
let askedOrder = [];
let sourceNumberToItemIndex = {};
let adaptiveEnabled = false;
let adaptiveState = {
    theta: 0,
    standardError: 999,
    itemsAnswered: 0,
    correctAnswers: 0
};
let totalQuestions = 0;
let currentPage = 0;
let answers = {};
let timerInterval;
let timeRemainingSeconds;

// Anti-cheating variables
let violationCount = 0;
const MAX_VIOLATIONS = 5;
let tabSwitchCount = 0;
let isExamActive = true;
let isSubmitting = false;
let isNavigationLocked = false;
let autoSubmitScheduled = false;
const CHOICE_LABEL_REGEX = '(?:[A-Z]{1,3}|\\d{1,4})';
const INLINE_DOT_CHOICE_LABEL_REGEX = '(?:[A-Z]|\\d{1,4})';

function decodeHtmlEntities(value) {
    const textarea = document.createElement('textarea');
    textarea.innerHTML = String(value || '');
    return textarea.value;
}

function normalizeQuestionMarkup(value) {
    let normalized = String(value || '').trim();
    if (!normalized) {
        return '';
    }

    normalized = normalized
        .replace(/<!--\s*(StartFragment|EndFragment)\s*-->/gi, '')
        .replace(/&lt;!--\s*(StartFragment|EndFragment)\s*--&gt;/gi, '');

    for (let pass = 0; pass < 4; pass++) {
        const hasEntityEncoding = /&(lt|gt|quot|amp|#\d+|#x[0-9a-f]+);/i.test(normalized);
        if (!hasEntityEncoding) {
            break;
        }
        const decoded = decodeHtmlEntities(normalized);
        if (decoded === normalized) {
            break;
        }
        normalized = decoded
            .replace(/<!--\s*(StartFragment|EndFragment)\s*-->/gi, '')
            .replace(/&lt;!--\s*(StartFragment|EndFragment)\s*--&gt;/gi, '');
    }

    return normalized.trim();
}

function sanitizeQuestionHtml(html) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(String(html || ''), 'text/html');
    if (!doc || !doc.body) {
        return '';
    }

    doc.body.querySelectorAll('script, style, iframe, object, embed, meta, link').forEach(el => el.remove());
    doc.body.querySelectorAll('*').forEach(el => {
        Array.from(el.attributes).forEach(attr => {
            const attrName = (attr.name || '').toLowerCase();
            const attrValue = String(attr.value || '');
            if (attrName.startsWith('on')) {
                el.removeAttribute(attr.name);
            }
            if ((attrName === 'href' || attrName === 'src') && /^\s*javascript:/i.test(attrValue)) {
                el.removeAttribute(attr.name);
            }
        });
    });

    return doc.body.innerHTML;
}

function escapeHtml(value) {
    return String(value || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function toPlainQuestionText(value) {
    return String(value || '')
        .replace(/<br\s*\/?>/gi, '\n')
        .replace(/<sup[^>]*>\s*(.*?)\s*<\/sup>/gi, '^$1')
        .replace(/<sub[^>]*>\s*(.*?)\s*<\/sub>/gi, '_$1')
        .replace(/<\/\s*(p|div|li|tr|h[1-6])\s*>/gi, '\n')
        .replace(/<li[^>]*>/gi, '')
        .replace(/<[^>]+>/g, ' ')
        .replace(/\u00A0/g, ' ')
        .replace(/\r/g, '\n')
        .replace(/[ \t]+\n/g, '\n')
        .replace(/\n{2,}/g, '\n')
        .replace(/[ \t]{2,}/g, ' ')
        .trim();
}

function parseChoiceLine(line) {
    const pattern = new RegExp(`^\\s*(?:\\(?(${CHOICE_LABEL_REGEX})\\)|(${CHOICE_LABEL_REGEX})[.)])\\s*(.+)$`, 'i');
    const match = String(line || '').match(pattern);
    if (!match) {
        return null;
    }
    const text = (match[3] || '').trim();
    if (!text) {
        return null;
    }
    return {
        label: (match[1] || match[2] || '').toUpperCase(),
        text
    };
}

function extractQuestionParts(rawQuestion) {
    const plain = toPlainQuestionText(rawQuestion);
    if (!plain) {
        return { stem: '', choices: [] };
    }

    const lines = plain.split(/\n+/).map(line => line.trim()).filter(Boolean);
    if (lines.length > 1) {
        const lineChoices = lines.slice(1)
            .map(parseChoiceLine)
            .filter(Boolean);
        if (lineChoices.length >= 2) {
            return {
                stem: lines[0],
                choices: lineChoices
            };
        }
    }

    const markerPattern = new RegExp(
        `(?:^|\\s)(?:\\(\\s*(${CHOICE_LABEL_REGEX})\\s*\\)|(${CHOICE_LABEL_REGEX})\\)|(${INLINE_DOT_CHOICE_LABEL_REGEX})\\.)(?=\\s+)`,
        'gi'
    );
    const markers = [];
    let match;
    while ((match = markerPattern.exec(plain)) !== null) {
        const fullMatch = match[0] || '';
        const markerToken = fullMatch.trimStart();
        const markerIndex = match.index + (fullMatch.length - markerToken.length);
        markers.push({
            index: markerIndex,
            endIndex: markerIndex + markerToken.length,
            label: (match[1] || match[2] || match[3] || '').toUpperCase()
        });
    }

    if (markers.length < 2) {
        return {
            stem: lines[0] || plain,
            choices: []
        };
    }

    const stem = plain.slice(0, markers[0].index).trim();
    const parsedChoices = [];
    for (let index = 0; index < markers.length; index++) {
        const current = markers[index];
        const next = markers[index + 1];
        const chunk = plain.slice(current.endIndex, next ? next.index : plain.length)
            .replace(/\s+/g, ' ')
            .trim();
        if (!chunk) {
            continue;
        }
        parsedChoices.push({
            label: current.label,
            text: chunk
        });
    }

    if (parsedChoices.length < 2) {
        return {
            stem: lines[0] || plain,
            choices: []
        };
    }

    return {
        stem: stem || (lines[0] || plain),
        choices: parsedChoices
    };
}

function getChoiceLabel(index) {
    if (!Number.isInteger(index) || index < 0) {
        return 'A';
    }

    let current = index;
    let label = '';
    do {
        const remainder = current % 26;
        label = String.fromCharCode(65 + remainder) + label;
        current = Math.floor(current / 26) - 1;
    } while (current >= 0);

    return label;
}

function getDefaultIrtParamsByDifficulty(difficulty) {
    const normalized = String(difficulty || 'Medium').trim().toLowerCase();
    if (normalized.startsWith('easy')) {
        return { a: 1.0, b: -0.8, c: 0.2 };
    }
    if (normalized.startsWith('hard')) {
        return { a: 1.4, b: 0.8, c: 0.2 };
    }
    return { a: 1.2, b: 0.0, c: 0.2 };
}

function normalizeExamItem(rawItem, fallbackDifficulty, index) {
    const isObject = rawItem && typeof rawItem === 'object' && !Array.isArray(rawItem);
    const questionRaw = isObject ? (rawItem.question || rawItem.text || '') : String(rawItem || '');
    const difficulty = isObject
        ? String(rawItem.difficulty || fallbackDifficulty || 'Medium')
        : String(fallbackDifficulty || 'Medium');
    const defaults = getDefaultIrtParamsByDifficulty(difficulty);

    const sourceQuestionNumberRaw = isObject ? Number(rawItem.sourceQuestionNumber) : NaN;
    const sourceQuestionNumber = Number.isInteger(sourceQuestionNumberRaw) && sourceQuestionNumberRaw > 0
        ? sourceQuestionNumberRaw
        : index + 1;

    const questionText = normalizeQuestionMarkup(questionRaw);
    const declaredType = isObject ? String(rawItem.questionType || '').toUpperCase() : '';
    const openEnded = declaredType === 'OPEN_ENDED' || questionText.includes('[TEXT_INPUT]');

    const item = {
        sourceQuestionNumber,
        question: questionText,
        difficulty,
        questionType: openEnded ? 'OPEN_ENDED' : 'MULTIPLE_CHOICE',
        irtA: isObject && Number.isFinite(Number(rawItem.irtA)) ? Number(rawItem.irtA) : defaults.a,
        irtB: isObject && Number.isFinite(Number(rawItem.irtB)) ? Number(rawItem.irtB) : defaults.b,
        irtC: isObject && Number.isFinite(Number(rawItem.irtC)) ? Number(rawItem.irtC) : defaults.c
    };

    item.irtA = Math.max(0.3, Math.min(3.0, item.irtA));
    item.irtB = Math.max(-3.0, Math.min(3.0, item.irtB));
    item.irtC = Math.max(0.0, Math.min(0.35, item.irtC));
    return item;
}

function getCurrentItemIndex() {
    if (!askedOrder.length) {
        return 0;
    }
    const safePage = Math.max(0, Math.min(currentPage, askedOrder.length - 1));
    const index = askedOrder[safePage];
    return Number.isInteger(index) ? index : 0;
}

function findFirstUnaskedItemIndex() {
    const used = new Set(askedOrder);
    for (let index = 0; index < examItems.length; index++) {
        if (!used.has(index)) {
            return index;
        }
    }
    return -1;
}

async function requestAdaptiveNext() {
    const distributedExamIdValue = examInfo && examInfo.distributedExamId ? String(examInfo.distributedExamId) : '';
    if (!distributedExamIdValue) {
        return { ok: true, done: true };
    }

    const askedSourceNumbers = askedOrder
        .map(index => examItems[index])
        .filter(Boolean)
        .map(item => Number(item.sourceQuestionNumber))
        .filter(value => Number.isInteger(value) && value > 0);

    const params = new URLSearchParams();
    params.append('distributedExamId', distributedExamIdValue);
    params.append('askedOrder', JSON.stringify(askedSourceNumbers));

    Object.entries(answers).forEach(([key, value]) => {
        params.append(key, String(value || ''));
    });

    const response = await fetch('/student/adaptive-next', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
        },
        credentials: 'same-origin',
        body: params.toString()
    });

    if (!response.ok) {
        throw new Error(`Adaptive next request failed (${response.status})`);
    }

    const payload = await response.json();
    if (payload && typeof payload === 'object') {
        adaptiveState.theta = Number(payload.theta || adaptiveState.theta || 0);
        adaptiveState.standardError = Number(payload.standardError || adaptiveState.standardError || 999);
        adaptiveState.itemsAnswered = Number(payload.itemsAnswered || adaptiveState.itemsAnswered || 0);
        adaptiveState.correctAnswers = Number(payload.correctAnswers || adaptiveState.correctAnswers || 0);
    }
    return payload;
}

/**
 * Initialize the exam from Thymeleaf data
 */
function initializeExam(examData, difficultiesData, examInfoData) {
    exam = examData;
    difficulties = difficultiesData;
    examInfo = examInfoData;

    const examArray = Array.isArray(examData) ? examData : [];
    const difficultyArray = Array.isArray(difficultiesData) ? difficultiesData : [];
    examItems = examArray.map((item, index) => normalizeExamItem(item, difficultyArray[index], index));
    totalQuestions = examItems.length;
    sourceNumberToItemIndex = {};
    examItems.forEach((item, index) => {
        sourceNumberToItemIndex[String(item.sourceQuestionNumber)] = index;
    });

    adaptiveEnabled = Boolean(examInfo && examInfo.adaptiveEnabled);
    askedOrder = [];

    if (adaptiveEnabled && totalQuestions > 0) {
        const firstSourceNumber = Number(examInfo.adaptiveFirstSourceQuestionNumber);
        const firstIndex = Number.isInteger(firstSourceNumber)
            ? sourceNumberToItemIndex[String(firstSourceNumber)]
            : undefined;
        askedOrder.push(Number.isInteger(firstIndex) ? firstIndex : 0);
    } else {
        askedOrder = examItems.map((_, index) => index);
    }

    if (!askedOrder.length && totalQuestions > 0) {
        askedOrder.push(0);
    }

    currentPage = 0;
    
    loadSavedAnswers();
    displayQuestion();
    initializeTimer();
    displayDeadline();
    initializeAntiCheating();
    lockNavigationDuringExam();
}

function appendExamMetaInputs(form) {
    const examIdValue = examInfo && examInfo.examId ? String(examInfo.examId) : '';
    const distributedExamIdValue = examInfo && examInfo.distributedExamId ? String(examInfo.distributedExamId) : '';

    if (examIdValue) {
        const examIdInput = document.createElement('input');
        examIdInput.type = 'hidden';
        examIdInput.name = 'examId';
        examIdInput.value = examIdValue;
        form.appendChild(examIdInput);
    }

    if (distributedExamIdValue) {
        const distributedExamIdInput = document.createElement('input');
        distributedExamIdInput.type = 'hidden';
        distributedExamIdInput.name = 'distributedExamId';
        distributedExamIdInput.value = distributedExamIdValue;
        form.appendChild(distributedExamIdInput);
    }

    if (askedOrder.length > 0) {
        const adaptiveOrderInput = document.createElement('input');
        adaptiveOrderInput.type = 'hidden';
        adaptiveOrderInput.name = 'adaptiveQuestionOrder';
        adaptiveOrderInput.value = JSON.stringify(
            askedOrder
                .map(index => examItems[index])
                .filter(Boolean)
                .map(item => item.sourceQuestionNumber)
        );
        form.appendChild(adaptiveOrderInput);
    }
}

function getOrCreateExamSubmitForm() {
    let form = document.getElementById('examForm');
    if (!form) {
        form = document.createElement('form');
        form.id = 'examForm';
        document.body.appendChild(form);
    }

    form.setAttribute('action', '/student/submit');
    form.setAttribute('method', 'post');
    form.setAttribute('accept-charset', 'UTF-8');
    form.innerHTML = '';
    return form;
}

function appendAnswerInputs(form) {
    for (const [key, value] of Object.entries(answers)) {
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = key;
        input.value = value;
        form.appendChild(input);
    }

    appendExamMetaInputs(form);
}

function submitExamFormNow(clearLocalStorage) {
    if (isSubmitting) {
        return;
    }

    isSubmitting = true;
    isExamActive = false;

    if (timerInterval) {
        clearInterval(timerInterval);
    }

    const form = getOrCreateExamSubmitForm();
    appendAnswerInputs(form);

    if (clearLocalStorage) {
        localStorage.removeItem('examAnswers');
    }

    form.submit();
}

function lockNavigationDuringExam() {
    if (isNavigationLocked) {
        return;
    }
    isNavigationLocked = true;

    history.pushState({ examLocked: true }, '', window.location.href);

    window.addEventListener('popstate', () => {
        if (isExamActive && !isSubmitting) {
            history.pushState({ examLocked: true }, '', window.location.href);
            showWarningModal('Finish and submit your exam before leaving this page.');
        }
    });
}

/**
 * Setup event listeners when DOM is ready
 */
function setupEventListeners() {
    // Navigation button event listeners
    document.getElementById('backBtn').addEventListener('click', navigateBack);
    document.getElementById('nextBtn').addEventListener('click', navigateNext);
}

/**
 * Main initialization function called from HTML
 */
window.startExam = function(examData, difficultiesData, examInfoData) {
    // Wait for DOM to be ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            initializeExam(examData, difficultiesData, examInfoData);
            setupEventListeners();
        });
    } else {
        // DOM already loaded
        initializeExam(examData, difficultiesData, examInfoData);
        setupEventListeners();
    }
};

/**
 * Load saved answers from localStorage
 */
function loadSavedAnswers() {
    const saved = localStorage.getItem('examAnswers');
    if (saved) {
        answers = JSON.parse(saved);
    }
}

/**
 * Auto-save answers to localStorage
 */
function autoSave() {
    localStorage.setItem('examAnswers', JSON.stringify(answers));
    showSaveIndicator();
}

/**
 * Show auto-save indicator
 */
function showSaveIndicator() {
    const indicator = document.getElementById('autoSaveIndicator');
    indicator.style.display = 'block';
    setTimeout(() => {
        indicator.style.display = 'none';
    }, 1500);
}

/**
 * Display current question
 */
function displayQuestion() {
    if (!examItems.length) {
        document.getElementById('questionContainer').innerHTML = '<div class="alert alert-warning">No questions available for this exam.</div>';
        return;
    }

    const itemIndex = getCurrentItemIndex();
    const activeItem = examItems[itemIndex] || {};
    let question = normalizeQuestionMarkup(activeItem.question || '');
    const questionNumber = currentPage + 1;

    // Use explicit type flag first, then marker fallback.
    const declaredOpenEnded = String(activeItem.questionType || '').toUpperCase() === 'OPEN_ENDED';
    const hasTextInputMarker = question.includes('[TEXT_INPUT]');
    if (hasTextInputMarker) {
        question = question.replace('[TEXT_INPUT]', '').trim();
    }
    
    // Remove difficulty markers like [Easy], [Medium], [Hard], [Essay], [Open-Ended]
    question = question.replace(/\[(Easy|Medium|Hard|Essay|Open-Ended|Open Ended|TEXT_INPUT)\]/gi, '').trim();

    // ── Extract [IMG:url] markers from question text ──────────────────────
    const imgPattern = /\[IMG:([^\]]+)\]/g;
    const imageUrls = [];
    let imgMatch;
    while ((imgMatch = imgPattern.exec(question)) !== null) {
        imageUrls.push(imgMatch[1]);
    }

    const vidPattern = /\[VID:([^\]]+)\]/g;
    const videoUrls = [];
    let vidMatch;
    while ((vidMatch = vidPattern.exec(question)) !== null) {
        videoUrls.push(vidMatch[1]);
    }

    // Remove the [IMG:...] markers from the text shown to students
    question = question.replace(/\[IMG:[^\]]+\]/g, '').trim();
    question = question.replace(/\[VID:[^\]]+\]/g, '').trim();

    const parsedQuestion = extractQuestionParts(question);
    const hasRenderableChoices = Array.isArray(parsedQuestion.choices) && parsedQuestion.choices.length >= 2;
    // Final safety net: if no choices are detected, render as open-ended so students always have an input field.
    const isTextInput = declaredOpenEnded || hasTextInputMarker || !hasRenderableChoices;

    // Build HTML for any extracted images
    let imagesHtml = '';
    if (imageUrls.length > 0) {
        imagesHtml = '<div class="question-media my-3">';
        imageUrls.forEach(url => {
            imagesHtml += `<img src="${url}" alt="Question image" class="img-fluid rounded shadow-sm border" style="max-height:400px; display:block; margin:8px auto;">`;
        });
        videoUrls.forEach(url => {
            imagesHtml += `<video src="${url}" controls class="img-fluid rounded shadow-sm border" style="max-height:400px; display:block; margin:8px auto; width:100%;"></video>`;
        });
        imagesHtml += '</div>';
    } else if (videoUrls.length > 0) {
        imagesHtml = '<div class="question-media my-3">';
        videoUrls.forEach(url => {
            imagesHtml += `<video src="${url}" controls class="img-fluid rounded shadow-sm border" style="max-height:400px; display:block; margin:8px auto; width:100%;"></video>`;
        });
        imagesHtml += '</div>';
    }
    
    let html = `<h5 class="mb-4">Question ${questionNumber}</h5>`;
    
    if (isTextInput) {
        const textPrompt = parsedQuestion.stem || question;
        const safeQuestionHtml = sanitizeQuestionHtml(textPrompt);
        // Text-input question (prefix hidden from student)
        html += `
            <p class="lead mb-2">${safeQuestionHtml}</p>
            ${imagesHtml}
            <div class="form-group">
                <label class="form-label fw-bold">Your Answer:</label>
                <textarea class="form-control" rows="4" 
                          id="textAnswer${questionNumber}"
                          placeholder="Type your answer here..."
                          onblur="saveTextAnswer(${questionNumber}, this.value)">${answers['q' + questionNumber] || ''}</textarea>
                <small class="form-text text-muted">This is an open-ended question. Provide a detailed answer.</small>
            </div>
        `;
    } else {
        // Multiple-choice question
        const questionText = parsedQuestion.stem || '';
        const choices = parsedQuestion.choices;
        const safeQuestionHtml = sanitizeQuestionHtml(questionText);
        
        html += `
            <p class="lead mb-2">${safeQuestionHtml}</p>
            ${imagesHtml}
            <div class="choices">
        `;
        
        choices.forEach((choice, idx) => {
            const choiceLetter = choice.label || getChoiceLabel(idx);
            const choiceText = String(choice.text || '').trim();
            if (!choiceText) {
                return;
            }
            const isSelected = answers['q' + questionNumber] === choiceText;
            const encodedChoice = encodeURIComponent(choiceText);
            
            html += `
                <button type="button" class="btn choice-btn w-100 ${isSelected ? 'selected' : ''}" 
                        onclick="selectAnswerEncoded(${questionNumber}, '${encodedChoice}', this)">
                    <strong>${escapeHtml(choiceLetter)})</strong> ${escapeHtml(choiceText)}
                </button>
            `;
        });
        
        html += '</div>';
    }
    
    document.getElementById('questionContainer').innerHTML = html;
    
    // Re-typeset MathJax equations in the newly rendered question
    if (window.MathJax && MathJax.typesetPromise) {
        MathJax.typesetPromise(['#questionContainer']).catch(err =>
            console.warn('MathJax render error:', err)
        );
    }
    
    // Update UI elements
    updateProgress();
    updateDifficultyBadge();
    
    // Update navigation buttons
    document.getElementById('backBtn').disabled = (currentPage === 0);
    document.getElementById('nextBtn').textContent = (currentPage === totalQuestions - 1) ? 'Submit' : 'Next';
}

/**
 * Save text answer for open-ended questions
 */
function saveTextAnswer(questionNumber, value) {
    if (value && value.trim()) {
        answers['q' + questionNumber] = value.trim();
    } else {
        delete answers['q' + questionNumber];
    }
    autoSave();
}

/**
 * Select an answer for multiple-choice question
 */
function selectAnswer(questionNum, answer, button) {
    // Remove selected class from all choices
    document.querySelectorAll('.choice-btn').forEach(btn => btn.classList.remove('selected'));
    
    // Add selected class to clicked choice
    button.classList.add('selected');
    
    // Save answer
    answers['q' + questionNum] = answer;
    autoSave();
}

function selectAnswerEncoded(questionNum, encodedAnswer, button) {
    const decodedAnswer = decodeURIComponent(encodedAnswer || '');
    selectAnswer(questionNum, decodedAnswer, button);
}

/**
 * Update progress bar and indicators
 */
function updateProgress() {
    const questionNum = currentPage + 1;
    const safeTotal = totalQuestions > 0 ? totalQuestions : 1;
    const percentage = (questionNum / safeTotal) * 100;
    
    document.getElementById('progressText').textContent = `Question ${questionNum} of ${totalQuestions}`;
    document.getElementById('progressBar').style.width = percentage + '%';
    document.getElementById('questionNumber').textContent = `${questionNum} / ${totalQuestions}`;
}

/**
 * Update difficulty badge based on current question
 */
function updateDifficultyBadge() {
    const difficultyBadge = document.getElementById('difficultyBadge');
    if (!difficultyBadge) return;

    const currentItem = examItems[getCurrentItemIndex()] || {};
    const currentDifficulty = currentItem.difficulty || difficulties[currentPage] || 'Medium';
    
    // Remove all difficulty classes
    difficultyBadge.classList.remove('difficulty-easy', 'difficulty-medium', 'difficulty-hard');
    
    // Add appropriate class and update text
    if (currentDifficulty.toLowerCase() === 'easy') {
        difficultyBadge.classList.add('difficulty-easy');
        difficultyBadge.textContent = 'Easy';
    } else if (currentDifficulty.toLowerCase() === 'hard') {
        difficultyBadge.classList.add('difficulty-hard');
        difficultyBadge.textContent = 'Hard';
    } else {
        difficultyBadge.classList.add('difficulty-medium');
        difficultyBadge.textContent = 'Medium';
    }
}

/**
 * Navigate to previous question
 */
function navigateBack() {
    if (currentPage > 0) {
        currentPage--;
        displayQuestion();
    }
}

/**
 * Navigate to next question or submit
 */
async function navigateNext() {
    if (currentPage < totalQuestions - 1) {
        if (currentPage < askedOrder.length - 1) {
            currentPage++;
            displayQuestion();
            return;
        }

        if (adaptiveEnabled && askedOrder.length < totalQuestions) {
            const nextBtn = document.getElementById('nextBtn');
            if (nextBtn) {
                nextBtn.disabled = true;
                nextBtn.textContent = 'Loading...';
            }

            try {
                const payload = await requestAdaptiveNext();
                if (payload && payload.done) {
                    const fallbackIndex = findFirstUnaskedItemIndex();
                    if (fallbackIndex >= 0 && !askedOrder.includes(fallbackIndex)) {
                        askedOrder.push(fallbackIndex);
                        currentPage++;
                        displayQuestion();
                        return;
                    }
                    submitExam();
                    return;
                }

                let nextIndex = -1;
                const nextSourceNumber = Number(payload && payload.nextSourceQuestionNumber);
                if (Number.isInteger(nextSourceNumber)) {
                    const mappedIndex = sourceNumberToItemIndex[String(nextSourceNumber)];
                    if (Number.isInteger(mappedIndex)) {
                        nextIndex = mappedIndex;
                    }
                }

                if (nextIndex < 0) {
                    nextIndex = findFirstUnaskedItemIndex();
                }

                if (nextIndex >= 0 && !askedOrder.includes(nextIndex)) {
                    askedOrder.push(nextIndex);
                    currentPage++;
                    displayQuestion();
                    return;
                }
            } catch (error) {
                console.warn('Adaptive next question failed, falling back to first unasked item.', error);
                const fallbackIndex = findFirstUnaskedItemIndex();
                if (fallbackIndex >= 0 && !askedOrder.includes(fallbackIndex)) {
                    askedOrder.push(fallbackIndex);
                    currentPage++;
                    displayQuestion();
                    return;
                }
            } finally {
                if (nextBtn) {
                    nextBtn.disabled = false;
                    nextBtn.textContent = (currentPage === totalQuestions - 1) ? 'Submit' : 'Next';
                }
            }
        }

        if (adaptiveEnabled && askedOrder.length < totalQuestions) {
            alert('Unable to load the next question right now. Please click Next again.');
            return;
        }
    }

    submitExam();
}

/**
 * Submit the exam
 */
function submitExam() {
    // Pause anti-cheat BEFORE confirm() so the dialog blur doesn't count as a violation
    isExamActive = false;

    if (confirm('Are you sure you want to submit your exam? You cannot change your answers after submission.')) {
        submitExamFormNow(true);
    } else {
        // Student cancelled — re-enable anti-cheat
        isExamActive = true;
    }
}

/**
 * Initialize timer
 */
function initializeTimer() {
    const timeLimitMinutes = parseInt(examInfo.timeLimit) || 60;
    const startTimeMillis = parseInt(examInfo.startTimeMillis);
    
    console.log('🕒 Initializing timer - Time limit:', timeLimitMinutes, 'minutes');
    console.log('🕒 Start time (epoch ms):', startTimeMillis);
    
    // Calculate remaining time using epoch milliseconds
    if (!startTimeMillis || isNaN(startTimeMillis)) {
        console.warn('⚠️ No valid start time provided, using full time limit');
        timeRemainingSeconds = timeLimitMinutes * 60;
    } else {
        const nowMillis = Date.now();
        const elapsedSeconds = Math.floor((nowMillis - startTimeMillis) / 1000);
        timeRemainingSeconds = (timeLimitMinutes * 60) - elapsedSeconds;
        
        console.log('🕒 Current time (epoch ms):', nowMillis);
        console.log('🕒 Elapsed:', elapsedSeconds, 'seconds');
        console.log('🕒 Remaining:', timeRemainingSeconds, 'seconds');
        
        // Minimum 5 seconds to prevent immediate auto-submit
        if (timeRemainingSeconds < 5) {
            console.warn('⚠️ Timer shows < 5 seconds. Check if this is correct!');
            if (elapsedSeconds < 5) {
                console.log('✅ Exam just started, using full time limit');
                timeRemainingSeconds = timeLimitMinutes * 60;
            } else {
                timeRemainingSeconds = 0;
            }
        }
    }
    
    console.log('✅ Timer initialized with', timeRemainingSeconds, 'seconds remaining');
    
    updateTimerDisplay();
    timerInterval = setInterval(() => {
        timeRemainingSeconds--;
        updateTimerDisplay();
        
        if (timeRemainingSeconds <= 0) {
            clearInterval(timerInterval);
            autoSubmitExam();
        }
    }, 1000);
}

/**
 * Update timer display
 */
function updateTimerDisplay() {
    const hours = Math.floor(timeRemainingSeconds / 3600);
    const minutes = Math.floor((timeRemainingSeconds % 3600) / 60);
    const seconds = timeRemainingSeconds % 60;
    
    const timerElement = document.getElementById('timerDisplay');
    const formattedTime = `${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
    timerElement.textContent = `Time: ${formattedTime}`;
    
    // Change color based on remaining time
    if (timeRemainingSeconds <= 60) {
        timerElement.className = 'badge bg-danger fs-6 timer-warning';
    } else if (timeRemainingSeconds <= 300) {
        timerElement.className = 'badge bg-warning text-dark fs-6 timer-warning';
    } else {
        timerElement.className = 'badge bg-success fs-6';
    }
}

/**
 * Display deadline
 */
function displayDeadline() {
    const deadline = examInfo.deadline;
    if (deadline) {
        const deadlineDate = new Date(deadline);
        const options = { 
            month: 'short', 
            day: 'numeric', 
            year: 'numeric',
            hour: '2-digit', 
            minute: '2-digit'
        };
        document.getElementById('deadlineDisplay').textContent = deadlineDate.toLocaleString('en-US', options);
        
        // Check deadline every minute
        setInterval(() => {
            checkDeadline(deadlineDate);
        }, 60000); // Check every 60 seconds
        
        // Also check immediately
        checkDeadline(deadlineDate);
    }
}

/**
 * Check if deadline has passed and auto-submit if needed
 */
function checkDeadline(deadlineDate) {
    const now = new Date();
    if (now > deadlineDate) {
        console.log('🚫 Deadline exceeded - auto-submitting exam');
        alert('The exam deadline has passed. Your exam will be automatically submitted.');
        autoSubmitExam();
    }
}

/**
 * Auto-submit exam when time expires
 */
function autoSubmitExam() {
    if (isSubmitting || autoSubmitScheduled) {
        return;
    }

    autoSubmitScheduled = true;
    isExamActive = false;

    // Show time's up modal
    showTimesUpModal();
    
    // Wait 3 seconds then submit
    setTimeout(() => {
        console.log('⏰ Time expired - force submitting exam');
        
        submitExamFormNow(false);
    }, 3000);
}

/**
 * Show Time's Up modal
 */
function showTimesUpModal() {
    if (document.getElementById('timesUpModal')) {
        return;
    }

    const modal = document.createElement('div');
    modal.id = 'timesUpModal';
    modal.innerHTML = `
        <div class="times-up-modal">
            <div class="times-up-card">
                <div class="times-up-icon"><i class="bi bi-alarm"></i></div>
                <h5 class="times-up-title mb-2">Time is up</h5>
                <p class="text-muted mb-2">
                    Your exam time has expired.
                </p>
                <p class="small text-muted mb-3">
                    Submitting your answers automatically...
                </p>
                <div>
                    <div class="spinner-border text-secondary" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                </div>
            </div>
        </div>
    `;
    document.body.appendChild(modal);
}

/**
 * Prevent accidental page leave
 */
window.addEventListener('beforeunload', (e) => {
    // Skip the guard when the student is intentionally submitting
    if (isSubmitting) return;
    if (Object.keys(answers).length > 0 && timeRemainingSeconds > 0) {
        e.preventDefault();
        e.returnValue = '';
    }
});
