// Auto-dismiss alerts after 5 seconds
    setTimeout(function() {
        var alerts = document.querySelectorAll('.alert');
        alerts.forEach(function(alert) {
            var bsAlert = new bootstrap.Alert(alert);
            bsAlert.close();
        });
    }, 5000);

    // Live LaTeX preview
    let previewDebounce;
    function goBackSmart(buttonEl) {
        const returnUrl = buttonEl ? buttonEl.getAttribute('data-return-url') : null;
        if (returnUrl) {
            window.location.href = returnUrl;
            return;
        }
        const directUrl = buttonEl ? buttonEl.getAttribute('data-processed-url') : null;
        if (directUrl) {
            window.location.href = directUrl;
            return;
        }
        window.location.href = '/teacher/processed-papers';
    }

    function updateMathPreview() {
        clearTimeout(previewDebounce);
        previewDebounce = setTimeout(function() {
            const editor = document.getElementById('questionEditor');
            if (!editor) return;
            const raw = editor.innerHTML || '';
            const hasLatex = /\$|\\\(|\\\[|<math/i.test(raw);
            const container = document.getElementById('mathPreviewContainer');
            const preview = document.getElementById('mathPreview');
            const plain = (editor.innerText || '').trim();
            if (!hasLatex || !plain) {
                container.style.display = 'none';
                return;
            }
            container.style.display = 'block';
            preview.innerHTML = raw;
            if (window.MathJax && MathJax.typesetPromise) {
                MathJax.typesetClear([preview]);
                MathJax.typesetPromise([preview]).catch(err => console.warn('MathJax preview error:', err));
            }
        }, 400);
    }

    function toSuperscriptToken(ch) {
        const map = {
            '\u00B0': '^\\circ',
            '\u00B9': '^1',
            '\u00B2': '^2',
            '\u00B3': '^3',
            '\u2070': '^0',
            '\u2074': '^4',
            '\u2075': '^5',
            '\u2076': '^6',
            '\u2077': '^7',
            '\u2078': '^8',
            '\u2079': '^9',
            '\u207A': '^+',
            '\u207B': '^-',
            '\u207C': '^=',
            '\u207D': '^(',
            '\u207E': '^)',
            '\u207F': '^n'
        };
        return map[ch] || null;
    }

    function toSubscriptToken(ch) {
        const map = {
            '\u2080': '_0',
            '\u2081': '_1',
            '\u2082': '_2',
            '\u2083': '_3',
            '\u2084': '_4',
            '\u2085': '_5',
            '\u2086': '_6',
            '\u2087': '_7',
            '\u2088': '_8',
            '\u2089': '_9',
            '\u208A': '_+',
            '\u208B': '_-',
            '\u208C': '_=',
            '\u208D': '_(',
            '\u208E': '_)'
        };
        return map[ch] || null;
    }

    function normalizeEquationArtifactsText(rawText, trimResult = true, convertEquationTokens = false) {
        if (rawText == null) {
            return '';
        }

        const symbolMap = {
            '\u00D7': '\\times',
            '\u00F7': '\\div',
            '\u2264': '\\le',
            '\u2265': '\\ge',
            '\u2260': '\\ne',
            '\u2248': '\\approx',
            '\u221E': '\\infty',
            '\u03C0': '\\pi',
            '\u03B1': '\\alpha',
            '\u03B2': '\\beta',
            '\u03B3': '\\gamma',
            '\u0394': '\\Delta',
            '\u03B8': '\\theta',
            '\u2211': '\\sum',
            '\u220F': '\\prod',
            '\u221A': '\\sqrt',
            '\u222B': '\\int'
        };

        const normalized = String(rawText)
            .replace(/\u00A0/g, ' ')
            .replace(/[\u200B\u200C\u200D\uFEFF]/g, '')
            .replace(/[\u2018\u2019]/g, "'")
            .replace(/[\u201C\u201D]/g, '"')
            .replace(/[\u2013\u2014\u2212]/g, '-')
            .replace(/\u2044/g, '/');

        let rebuilt = '';
        for (const ch of normalized) {
            if (convertEquationTokens) {
                const superscript = toSuperscriptToken(ch);
                if (superscript) {
                    rebuilt += superscript;
                    continue;
                }

                const subscript = toSubscriptToken(ch);
                if (subscript) {
                    rebuilt += subscript;
                    continue;
                }

                if (symbolMap[ch]) {
                    rebuilt += symbolMap[ch];
                    continue;
                }
            }

            rebuilt += ch;
        }

        const normalizedWhitespace = rebuilt
            .replace(/[\t\x0B\f\r]+/g, ' ')
            .replace(/ *\n */g, '\n')
            .replace(/\n{3,}/g, '\n\n');

        return trimResult ? normalizedWhitespace.trim() : normalizedWhitespace;
    }

    function normalizeEquationArtifactsInHtml(rawHtml, convertEquationTokens = false) {
        if (!rawHtml) {
            return '';
        }

        const parser = new DOMParser();
        const doc = parser.parseFromString(`<div id="mq-normalize-root">${rawHtml}</div>`, 'text/html');
        const root = doc.getElementById('mq-normalize-root');
        if (!root) {
            return normalizeEquationArtifactsText(rawHtml, true, convertEquationTokens);
        }

        const walker = doc.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        const textNodes = [];
        while (walker.nextNode()) {
            textNodes.push(walker.currentNode);
        }

        textNodes.forEach(node => {
            node.nodeValue = normalizeEquationArtifactsText(node.nodeValue || '', false, convertEquationTokens);
        });

        return (root.innerHTML || '').trim();
    }

    function shouldPreferPlainTextPaste(rawHtml, plainText) {
        if (!rawHtml || !rawHtml.trim()) {
            return true;
        }

        if (!plainText || !plainText.trim()) {
            return false;
        }

        const parser = new DOMParser();
        const doc = parser.parseFromString(rawHtml, 'text/html');
        const body = doc && doc.body ? doc.body : null;
        if (!body) {
            return true;
        }

        const hasComplexMathMarkup = !!body.querySelector('math, mrow, mfrac, msup, msub, msqrt, mtable, svg, mjx-container, .MathJax, .katex, .katex-mathml');
        if (hasComplexMathMarkup) {
            return true;
        }

        const htmlText = (body.textContent || '').replace(/\s+/g, ' ').trim();
        const plain = String(plainText || '').replace(/\s+/g, ' ').trim();
        if (!htmlText && plain) {
            return true;
        }

        return false;
    }

    function toggleAddQuestionTypeFields() {
        const typeSelect = document.getElementById('addQuestionType');
        const choicesContainer = document.getElementById('mcqChoicesContainer');
        const choicesText = document.getElementById('addChoicesText');
        const answerInput = document.getElementById('addAnswerInput');
        const openEndedAnswerInput = document.getElementById('addOpenEndedAnswerInput');
        const answerHint = document.getElementById('addAnswerHint');
        const openEndedHint = document.getElementById('openEndedHintContainer');

        if (!typeSelect || !choicesContainer || !choicesText || !answerInput || !answerHint || !openEndedHint) return;

        const isOpenEnded = typeSelect.value === 'OPEN_ENDED';
        choicesContainer.style.display = isOpenEnded ? 'none' : '';
        openEndedHint.style.display = isOpenEnded ? '' : 'none';
        if (isOpenEnded) {
            choicesText.value = '';
            const referenceAnswer = openEndedAnswerInput ? (openEndedAnswerInput.value || '').trim() : '';
            answerInput.value = referenceAnswer || 'MANUAL_GRADE';
            document.querySelectorAll('#choicesInputs .choice-input-row').forEach(row => {
                row.classList.remove('is-correct-choice');
                const btn = row.querySelector('.choice-correct-btn');
                if (btn) {
                    btn.classList.remove('is-correct');
                    btn.setAttribute('aria-pressed', 'false');
                }
            });
            answerHint.innerHTML = '<i class="bi bi-check-circle me-1"></i>Open-ended questions are graded manually (optional reference answer supported).';
        } else {
            answerHint.innerHTML = '<i class="bi bi-check-circle me-1"></i>Check one choice as the correct answer.';
            syncAddChoicesText();
        }
    }

    function toggleAddMediaTypeFields() {
        const mediaType = document.getElementById('addMediaType');
        const imageWrap = document.getElementById('addImageUploadContainer');
        const videoWrap = document.getElementById('addVideoUploadContainer');
        if (!mediaType || !imageWrap || !videoWrap) return;

        const imageInput = imageWrap.querySelector('input[name="questionImage"]');
        const videoInput = videoWrap.querySelector('input[name="questionVideo"]');
        imageWrap.style.display = mediaType.value === 'IMAGE' ? '' : 'none';
        videoWrap.style.display = mediaType.value === 'VIDEO' ? '' : 'none';

        if (mediaType.value !== 'IMAGE' && imageInput) {
            imageInput.value = '';
        }
        if (mediaType.value !== 'VIDEO' && videoInput) {
            videoInput.value = '';
        }
    }

    function toggleEditQuestionTypeFields(selectEl) {
        if (!selectEl) return;
        const form = selectEl.closest('form');
        if (!form) return;
        const answerInput = form.querySelector('.edit-answer-input');
        if (!answerInput) return;

        const isOpenEnded = selectEl.value === 'OPEN_ENDED';
        answerInput.required = !isOpenEnded;
        answerInput.readOnly = false;
        if (isOpenEnded) {
            if ((answerInput.value || '').trim().toUpperCase() === 'MANUAL_GRADE') {
                answerInput.value = '';
            }
            answerInput.placeholder = 'Reference answer (optional)';
        } else {
            answerInput.placeholder = 'Correct answer';
        }
    }

    function toggleEditMediaTypeFields(selectEl) {
        if (!selectEl) return;
        const form = selectEl.closest('form');
        if (!form) return;

        const imageWrap = form.querySelector('.edit-image-upload');
        const videoWrap = form.querySelector('.edit-video-upload');
        if (!imageWrap || !videoWrap) return;

        const imageInput = imageWrap.querySelector('input[name="questionImage"]');
        const videoInput = videoWrap.querySelector('input[name="questionVideo"]');
        imageWrap.style.display = selectEl.value === 'IMAGE' ? '' : 'none';
        videoWrap.style.display = selectEl.value === 'VIDEO' ? '' : 'none';

        if (selectEl.value !== 'IMAGE' && imageInput) {
            imageInput.value = '';
        }
        if (selectEl.value !== 'VIDEO' && videoInput) {
            videoInput.value = '';
        }
    }

    function syncAddChoicesText() {
        const hidden = document.getElementById('addChoicesText');
        const answerInput = document.getElementById('addAnswerInput');
        const openEndedAnswerInput = document.getElementById('addOpenEndedAnswerInput');
        if (!hidden) return;

        if (openEndedAnswerInput) {
            const normalizedReference = normalizeEquationArtifactsText(openEndedAnswerInput.value || '');
            if (normalizedReference !== openEndedAnswerInput.value) {
                openEndedAnswerInput.value = normalizedReference;
            }
        }

        const rows = Array.from(document.querySelectorAll('#choicesInputs .choice-input-row'));
        const values = rows
            .map(row => row.querySelector('.add-choice-input'))
            .filter(Boolean)
            .map(input => {
                const normalizedValue = normalizeEquationArtifactsText(input.value || '');
                if (normalizedValue !== input.value) {
                    input.value = normalizedValue;
                }
                return normalizedValue;
            })
            .map(value => (value || '').trim())
            .filter(Boolean);

        hidden.value = values.join('\n');

        if (answerInput) {
            const selectedRow = rows.find(row => row.classList.contains('is-correct-choice'));
            const selectedInput = selectedRow ? selectedRow.querySelector('.add-choice-input') : null;
            const selectedValue = selectedInput ? (selectedInput.value || '').trim() : '';
            const isOpenEnded = document.getElementById('addQuestionType')?.value === 'OPEN_ENDED';
            const referenceAnswer = openEndedAnswerInput ? (openEndedAnswerInput.value || '').trim() : '';
            answerInput.value = isOpenEnded ? (referenceAnswer || 'MANUAL_GRADE') : selectedValue;
        }
    }

    function addChoiceInput(value = '') {
        const container = document.getElementById('choicesInputs');
        if (!container) return;

        const row = document.createElement('div');
        row.className = 'choice-input-row';

        const correctBtn = document.createElement('button');
        correctBtn.type = 'button';
        correctBtn.className = 'btn btn-sm choice-correct-btn';
        correctBtn.innerHTML = '<i class="bi bi-check-lg"></i>';
        correctBtn.title = 'Mark as Correct Answer';
        correctBtn.setAttribute('aria-pressed', 'false');
        correctBtn.addEventListener('click', function () {
            document.querySelectorAll('#choicesInputs .choice-input-row').forEach(otherRow => {
                otherRow.classList.remove('is-correct-choice');
                const otherBtn = otherRow.querySelector('.choice-correct-btn');
                if (otherBtn) {
                    otherBtn.classList.remove('is-correct');
                    otherBtn.setAttribute('aria-pressed', 'false');
                }
            });
            row.classList.add('is-correct-choice');
            correctBtn.classList.add('is-correct');
            correctBtn.setAttribute('aria-pressed', 'true');
            syncAddChoicesText();
        });

        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'form-control add-choice-input';
        input.placeholder = 'Enter choice';
        input.value = normalizeEquationArtifactsText(value || '');
        input.addEventListener('input', syncAddChoicesText);
        input.addEventListener('blur', function () {
            const normalizedValue = normalizeEquationArtifactsText(input.value || '');
            if (normalizedValue !== input.value) {
                input.value = normalizedValue;
                syncAddChoicesText();
            }
        });

        const removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'btn btn-sm choice-remove-btn';
        removeBtn.innerHTML = '<i class="fa-solid fa-xmark"></i>';
        removeBtn.title = 'Remove Choice';
        removeBtn.addEventListener('click', function () {
            row.remove();
            syncAddChoicesText();
        });

        row.appendChild(correctBtn);
        row.appendChild(input);
        row.appendChild(removeBtn);
        container.appendChild(row);
        syncAddChoicesText();
    }

    const manageQuestionSearch = document.getElementById('manageQuestionSearch');
    if (manageQuestionSearch) {
        manageQuestionSearch.addEventListener('input', function () {
            const query = this.value.toLowerCase().trim();
            document.querySelectorAll('.question-card').forEach(card => {
                const key = (card.getAttribute('data-question-search') || '').toLowerCase();
                card.style.display = (!query || key.includes(query)) ? '' : 'none';
            });
        });
    }

    const MAX_VIDEO_SIZE_BYTES = 500 * 1024 * 1024; // 500MB
    function validateVideoFileSize(inputEl) {
        if (!inputEl || !inputEl.files || inputEl.files.length === 0) {
            return true;
        }
        const file = inputEl.files[0];
        if (file.size > MAX_VIDEO_SIZE_BYTES) {
            alert('Video is too large. Maximum allowed size is 500MB.');
            inputEl.value = '';
            return false;
        }
        return true;
    }

    document.querySelectorAll('input[name="questionVideo"]').forEach(inputEl => {
        inputEl.addEventListener('change', function () {
            validateVideoFileSize(this);
        });
    });

    document.querySelectorAll('form[action*="/teacher/add-question"], form[action*="/teacher/edit-question"]').forEach(formEl => {
        formEl.addEventListener('submit', function (event) {
            const videoInput = this.querySelector('input[name="questionVideo"]');
            if (!validateVideoFileSize(videoInput)) {
                event.preventDefault();
            }
        });
    });

    document.addEventListener('DOMContentLoaded', function () {
        const addChoiceBtn = document.getElementById('addChoiceBtn');
        const existingChoices = document.querySelectorAll('.add-choice-input').length;
        if (existingChoices === 0) {
            addChoiceInput('Choice A');
            addChoiceInput('Choice B');
            addChoiceInput('Choice C');
            addChoiceInput('Choice D');
        }
        if (addChoiceBtn) {
            addChoiceBtn.addEventListener('click', function () {
                addChoiceInput('');
            });
        }

        const addOpenEndedAnswerInput = document.getElementById('addOpenEndedAnswerInput');
        if (addOpenEndedAnswerInput) {
            addOpenEndedAnswerInput.addEventListener('input', syncAddChoicesText);
            addOpenEndedAnswerInput.addEventListener('blur', function () {
                const normalizedValue = normalizeEquationArtifactsText(addOpenEndedAnswerInput.value || '');
                if (normalizedValue !== addOpenEndedAnswerInput.value) {
                    addOpenEndedAnswerInput.value = normalizedValue;
                }
                syncAddChoicesText();
            });
        }

        toggleAddQuestionTypeFields();
        toggleAddMediaTypeFields();
        document.querySelectorAll('.edit-question-type').forEach(selectEl => {
            toggleEditQuestionTypeFields(selectEl);
        });
        document.querySelectorAll('.edit-media-type').forEach(selectEl => {
            toggleEditMediaTypeFields(selectEl);
        });

        function getToolbarEditor(toolbarEl) {
            if (!toolbarEl) return null;
            const selector = toolbarEl.getAttribute('data-editor');
            if (selector) {
                return document.querySelector(selector);
            }
            const block = toolbarEl.closest('.editor-block');
            return block ? block.querySelector('.rich-editor') : null;
        }

        const savedRanges = new WeakMap();
        const DEFAULT_FONT_SIZE = '11pt';

        function saveSelection(editor) {
            if (!editor) return;
            const selection = window.getSelection();
            if (!selection || selection.rangeCount === 0) return;
            const range = selection.getRangeAt(0);
            if (!editor.contains(range.commonAncestorContainer)) return;
            savedRanges.set(editor, range.cloneRange());
        }

        function restoreSelection(editor) {
            if (!editor) return;
            const saved = savedRanges.get(editor);
            if (!saved) return;
            const selection = window.getSelection();
            if (!selection) return;
            selection.removeAllRanges();
            selection.addRange(saved);
        }

        function focusEditor(editor) {
            if (!editor) return;
            editor.focus();
        }

        function readStyleFromEditor(editor) {
            if (!editor) {
                return { family: 'Default', size: '--' };
            }

            let target = editor;
            const selection = window.getSelection();
            if (selection && selection.rangeCount > 0) {
                const range = selection.getRangeAt(0);
                if (editor.contains(range.commonAncestorContainer)) {
                    const node = range.startContainer;
                    target = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
                }
            }

            const computed = window.getComputedStyle(target);
            const familyRaw = (computed.fontFamily || 'Default').split(',')[0].replace(/["']/g, '').trim();
            const sizeRaw = parseFloat(computed.fontSize || '0');
            return {
                family: familyRaw || 'Default',
                size: Number.isFinite(sizeRaw) && sizeRaw > 0 ? `${Math.round(sizeRaw)}px` : '--'
            };
        }

        function updateToolbarStyleStatus(toolbar, editor) {
            if (!toolbar || !editor) return;
            const style = readStyleFromEditor(editor);
            const fontSelect = toolbar.querySelector('[data-editor-font-family]');
            const sizeSelect = toolbar.querySelector('[data-editor-font-size]');

            if (fontSelect) {
                const options = Array.from(fontSelect.options);
                const found = options.find(opt => (opt.value || '').toLowerCase() === (style.family || '').toLowerCase());
                if (found) {
                    fontSelect.value = found.value;
                }
            }

            if (sizeSelect) {
                const sizePx = parseFloat(String(style.size || '').replace(/[^0-9.]/g, ''));
                if (Number.isFinite(sizePx) && sizePx > 0) {
                    const sizePt = sizePx * (72 / 96);
                    let closest = null;
                    let minDiff = Number.POSITIVE_INFINITY;
                    Array.from(sizeSelect.options).forEach(opt => {
                        const optPt = parseFloat(String(opt.value || '').replace(/[^0-9.]/g, ''));
                        if (!Number.isFinite(optPt)) return;
                        const diff = Math.abs(optPt - sizePt);
                        if (diff < minDiff) {
                            minDiff = diff;
                            closest = opt;
                        }
                    });
                    if (closest) {
                        sizeSelect.value = closest.value;
                    }
                } else if (sizeSelect.value !== DEFAULT_FONT_SIZE) {
                    sizeSelect.value = DEFAULT_FONT_SIZE;
                }
            }
        }

        function updateAllToolbarStatuses() {
            document.querySelectorAll('.editor-toolbar').forEach(toolbar => {
                const editor = getToolbarEditor(toolbar);
                updateToolbarStyleStatus(toolbar, editor);
            });
        }

        function applyEditorCommand(editor, command, value = null) {
            if (!editor) return;
            focusEditor(editor);
            restoreSelection(editor);
            document.execCommand('styleWithCSS', false, true);
            if (value === null) {
                document.execCommand(command, false);
            } else {
                document.execCommand(command, false, value);
            }
            saveSelection(editor);
            if (editor.id === 'questionEditor') {
                updateMathPreview();
            }
            updateAllToolbarStatuses();
        }

        function applyFontSize(editor, sizeValue) {
            if (!editor || !sizeValue) return;
            focusEditor(editor);
            restoreSelection(editor);
            document.execCommand('styleWithCSS', false, false);
            document.execCommand('fontSize', false, '7');

            editor.querySelectorAll('font[size]').forEach(el => {
                if (el.getAttribute('size') !== '7') return;
                const span = document.createElement('span');
                span.style.fontSize = sizeValue;
                span.innerHTML = el.innerHTML;
                el.replaceWith(span);
            });

            editor.querySelectorAll('[style]').forEach(el => {
                const raw = String(el.style.fontSize || '').trim().toLowerCase();
                if (!raw) return;
                if (raw === 'xx-small' || raw === 'x-small' || raw === 'small' || raw === 'medium'
                    || raw === 'large' || raw === 'x-large' || raw === 'xx-large' || raw === 'xxx-large'
                    || raw === '-webkit-xxx-large' || raw === '-webkit-xx-large') {
                    el.style.fontSize = sizeValue;
                }
            });

            saveSelection(editor);
            if (editor.id === 'questionEditor') {
                updateMathPreview();
            }
            updateAllToolbarStatuses();
            focusEditor(editor);
            restoreSelection(editor);
            saveSelection(editor);
        }

        function applyFontFamily(editor, fontFamily) {
            if (!editor || !fontFamily) return;
            focusEditor(editor);
            restoreSelection(editor);
            document.execCommand('styleWithCSS', false, false);
            document.execCommand('fontName', false, `"${fontFamily}"`);

            editor.querySelectorAll('font[face]').forEach(el => {
                const span = document.createElement('span');
                span.style.fontFamily = fontFamily;
                span.innerHTML = el.innerHTML;
                el.replaceWith(span);
            });

            saveSelection(editor);
            if (editor.id === 'questionEditor') {
                updateMathPreview();
            }
            updateAllToolbarStatuses();
            focusEditor(editor);
            restoreSelection(editor);
            saveSelection(editor);
        }

        function sanitizePastedHtml(rawHtml) {
            if (!rawHtml) return '';

            let sourceHtml = String(rawHtml || '');
            for (let pass = 0; pass < 4; pass++) {
                const hasEntityEncoding = /&(lt|gt|quot|amp|#\d+|#x[0-9a-f]+);/i.test(sourceHtml);
                if (!hasEntityEncoding) {
                    break;
                }
                const decoder = document.createElement('textarea');
                decoder.innerHTML = sourceHtml;
                const decoded = decoder.value;
                if (decoded === sourceHtml) {
                    break;
                }
                sourceHtml = decoded;
            }

            sourceHtml = sourceHtml
                .replace(/<!--\s*(StartFragment|EndFragment)\s*-->/gi, '')
                .replace(/&lt;!--\s*(StartFragment|EndFragment)\s*--&gt;/gi, '');

            const parser = new DOMParser();
            const doc = parser.parseFromString(sourceHtml, 'text/html');
            if (!doc || !doc.body) return '';

            doc.body.querySelectorAll('script, style, meta, link, iframe, object, embed').forEach(el => el.remove());

            const removeFragmentComments = (root) => {
                const walker = document.createTreeWalker(root, NodeFilter.SHOW_COMMENT);
                const comments = [];
                while (walker.nextNode()) {
                    comments.push(walker.currentNode);
                }
                comments.forEach(commentNode => {
                    const content = (commentNode.nodeValue || '').trim().toLowerCase();
                    if (content === 'startfragment' || content === 'endfragment') {
                        commentNode.parentNode && commentNode.parentNode.removeChild(commentNode);
                    }
                });
            };
            removeFragmentComments(doc.body);

            doc.body.querySelectorAll('mark').forEach(markEl => {
                const span = doc.createElement('span');
                span.innerHTML = markEl.innerHTML;
                markEl.replaceWith(span);
            });

            doc.body.querySelectorAll('*').forEach(el => {
                Array.from(el.attributes).forEach(attr => {
                    const attrName = (attr.name || '').toLowerCase();
                    if (attrName.startsWith('on')) {
                        el.removeAttribute(attr.name);
                    }
                    if (attrName === 'bgcolor') {
                        el.removeAttribute(attr.name);
                    }
                });

                const styleValue = el.getAttribute('style');
                if (!styleValue) {
                    return;
                }

                const filtered = styleValue
                    .split(';')
                    .map(rule => rule.trim())
                    .filter(Boolean)
                    .filter(rule => {
                        const property = rule.split(':')[0]?.trim().toLowerCase() || '';
                        return property !== 'background'
                            && property !== 'background-color'
                            && property !== 'background-image'
                            && property !== 'background-repeat'
                            && property !== 'background-position'
                            && property !== 'background-size'
                            && property !== 'mso-highlight';
                    });

                if (filtered.length > 0) {
                    el.setAttribute('style', filtered.join('; '));
                } else {
                    el.removeAttribute('style');
                }
            });

            return (doc.body.innerHTML || '').trim();
        }

        function insertEditorContent(editor, htmlContent, plainTextFallback) {
            if (!editor) return;
            focusEditor(editor);
            restoreSelection(editor);

            if (htmlContent && htmlContent.trim()) {
                document.execCommand('insertHTML', false, htmlContent);
            } else if (plainTextFallback) {
                document.execCommand('insertText', false, plainTextFallback);
            }

            saveSelection(editor);
            if (editor.id === 'questionEditor') {
                updateMathPreview();
            }
            updateAllToolbarStatuses();
        }

        document.querySelectorAll('.editor-toolbar [data-editor-font-family]').forEach(selectEl => {
            selectEl.addEventListener('mousedown', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                saveSelection(editor);
            });
            selectEl.addEventListener('focus', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                saveSelection(editor);
            });
            selectEl.addEventListener('change', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                if (!this.value) return;
                applyFontFamily(editor, this.value);
            });
        });

        document.querySelectorAll('.rich-editor').forEach(editor => {
            editor.addEventListener('paste', function (event) {
                event.preventDefault();
                const clipboard = event.clipboardData || window.clipboardData;
                const rawHtml = clipboard ? clipboard.getData('text/html') : '';
                const plainText = clipboard ? clipboard.getData('text/plain') : '';
                const cleanHtml = sanitizePastedHtml(rawHtml);
                const normalizedHtml = normalizeEquationArtifactsInHtml(cleanHtml, false);
                const normalizedText = normalizeEquationArtifactsText(plainText, true, false);
                if (shouldPreferPlainTextPaste(cleanHtml, normalizedText)) {
                    insertEditorContent(editor, '', normalizedText);
                    return;
                }
                insertEditorContent(editor, normalizedHtml, normalizedText);
            });
            editor.addEventListener('mouseup', function () {
                saveSelection(editor);
                updateAllToolbarStatuses();
            });
            editor.addEventListener('keyup', function () {
                saveSelection(editor);
                updateAllToolbarStatuses();
            });
            editor.addEventListener('input', function () {
                saveSelection(editor);
                updateAllToolbarStatuses();
            });
            editor.addEventListener('focus', function () {
                saveSelection(editor);
                updateAllToolbarStatuses();
            });
        });

        document.querySelectorAll('.editor-toolbar').forEach(toolbar => {
            toolbar.addEventListener('mousedown', function (event) {
                const editor = getToolbarEditor(toolbar);
                saveSelection(editor);
                if (event.target.closest('button')) {
                    event.preventDefault();
                }
            });
        });

        document.addEventListener('selectionchange', function () {
            updateAllToolbarStatuses();
        });

        document.querySelectorAll('.editor-toolbar [data-editor-cmd]').forEach(btn => {
            btn.addEventListener('click', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                if (!editor) return;

                const cmd = this.getAttribute('data-editor-cmd');
                if (cmd === 'clear') {
                    applyEditorCommand(editor, 'removeFormat');
                    return;
                }
                if (cmd === 'remove-bg') {
                    applyEditorCommand(editor, 'hiliteColor', 'transparent');
                    applyEditorCommand(editor, 'backColor', 'transparent');
                    return;
                }
                applyEditorCommand(editor, cmd);
            });
        });

        document.querySelectorAll('.editor-toolbar [data-editor-font-size]').forEach(selectEl => {
            selectEl.addEventListener('mousedown', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                saveSelection(editor);
            });
            selectEl.addEventListener('focus', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                saveSelection(editor);
            });
            selectEl.addEventListener('change', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                if (!this.value) return;
                applyFontSize(editor, this.value);
            });
        });

        document.querySelectorAll('.editor-toolbar [data-editor-action="pick-text-color"]').forEach(btn => {
            btn.addEventListener('click', function () {
                const toolbar = this.closest('.editor-toolbar');
                const picker = toolbar ? toolbar.querySelector('[data-editor-color]') : null;
                if (picker) picker.click();
            });
        });

        document.querySelectorAll('.editor-toolbar [data-editor-action="pick-highlight"]').forEach(btn => {
            btn.addEventListener('click', function () {
                const toolbar = this.closest('.editor-toolbar');
                const picker = toolbar ? toolbar.querySelector('[data-editor-highlight]') : null;
                if (picker) picker.click();
            });
        });

        document.querySelectorAll('.editor-toolbar [data-editor-color]').forEach(inputEl => {
            inputEl.addEventListener('input', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                applyEditorCommand(editor, 'foreColor', this.value);
            });
        });

        document.querySelectorAll('.editor-toolbar [data-editor-highlight]').forEach(inputEl => {
            inputEl.addEventListener('input', function () {
                const toolbar = this.closest('.editor-toolbar');
                const editor = getToolbarEditor(toolbar);
                applyEditorCommand(editor, 'hiliteColor', this.value);
            });
        });

        updateAllToolbarStatuses();

        const addForm = document.querySelector('form[action*="/teacher/add-question"]');
        if (addForm) {
            addForm.addEventListener('submit', function (event) {
                const editor = document.getElementById('questionEditor');
                const input = document.getElementById('questionTextInput');
                const typeSelect = document.getElementById('addQuestionType');
                if (!editor || !input) return;

                const normalizedHtml = normalizeEquationArtifactsInHtml(editor.innerHTML || '');
                if (normalizedHtml !== (editor.innerHTML || '')) {
                    editor.innerHTML = normalizedHtml;
                    updateMathPreview();
                }

                const plain = (editor.innerText || '').trim();
                if (!plain) {
                    event.preventDefault();
                    alert('Question text is required.');
                    return;
                }

                syncAddChoicesText();
                if (typeSelect && typeSelect.value === 'MULTIPLE_CHOICE') {
                    const choices = Array.from(document.querySelectorAll('.add-choice-input'))
                        .map(choice => (choice.value || '').trim())
                        .filter(Boolean);
                    if (choices.length < 2) {
                        event.preventDefault();
                        alert('Add at least 2 choices for multiple choice questions.');
                        return;
                    }

                    const answerInput = document.getElementById('addAnswerInput');
                    if (!answerInput || !(answerInput.value || '').trim()) {
                        event.preventDefault();
                        alert('Check one choice as the correct answer.');
                        return;
                    }
                } else {
                    const answerInput = document.getElementById('addAnswerInput');
                    if (answerInput) {
                        const openEndedAnswerInput = document.getElementById('addOpenEndedAnswerInput');
                        const referenceAnswer = openEndedAnswerInput ? (openEndedAnswerInput.value || '').trim() : '';
                        answerInput.value = referenceAnswer || 'MANUAL_GRADE';
                    }
                }

                const openEndedAnswerInput = document.getElementById('addOpenEndedAnswerInput');
                if (openEndedAnswerInput) {
                    openEndedAnswerInput.value = normalizeEquationArtifactsText(openEndedAnswerInput.value || '');
                }

                input.value = normalizeEquationArtifactsInHtml(editor.innerHTML || '');
            });
        }

        document.querySelectorAll('form[action*="/teacher/edit-question"]').forEach(formEl => {
            formEl.addEventListener('submit', function (event) {
                const editor = this.querySelector('.rich-editor-edit');
                const input = this.querySelector('.edit-question-text-input');
                if (!editor || !input) return;

                const normalizedHtml = normalizeEquationArtifactsInHtml(editor.innerHTML || '');
                if (normalizedHtml !== (editor.innerHTML || '')) {
                    editor.innerHTML = normalizedHtml;
                }

                const plain = (editor.innerText || '').trim();
                if (!plain) {
                    event.preventDefault();
                    alert('Question text is required.');
                    return;
                }

                const answerInput = this.querySelector('.edit-answer-input');
                if (answerInput) {
                    answerInput.value = normalizeEquationArtifactsText(answerInput.value || '');
                }

                input.value = normalizeEquationArtifactsInHtml(editor.innerHTML || '');
            });
        });
    });
