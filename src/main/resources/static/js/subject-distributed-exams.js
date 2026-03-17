document.addEventListener('DOMContentLoaded', function () {
        const input = document.getElementById('distributedExamsSearch');
        if (input) {
            input.addEventListener('input', function () {
                const query = this.value.toLowerCase().trim();
                document.querySelectorAll('#distributedExamsTable tbody tr').forEach(row => {
                    const key = row.getAttribute('data-search-key') || '';
                    row.style.display = (!query || key.includes(query)) ? '' : 'none';
                });
            });
        }

        const batchModalEl = document.getElementById('reopenBatchModal');
        if (batchModalEl && batchModalEl.parentElement !== document.body) {
            document.body.appendChild(batchModalEl);
        }
        if (!batchModalEl || typeof bootstrap === 'undefined') {
            return;
        }

        const batchLabel = document.getElementById('reopenBatchLabel');
        const examIdInput = document.getElementById('reopenBatchExamId');
        const examNameInput = document.getElementById('reopenBatchExamName');
        const activityTypeInput = document.getElementById('reopenBatchActivityType');
        const timeLimitInput = document.getElementById('reopenBatchTimeLimit');
        const deadlineInput = document.getElementById('reopenBatchDeadline');
        const distributionIdInput = document.getElementById('reopenBatchDistributionId');
        const presetSelect = document.getElementById('reopenBatchPreset');
        const customWrap = document.getElementById('reopenBatchCustomWrap');
        const customInput = document.getElementById('reopenBatchCustomDeadline');

        function syncBatchCustomDeadlineState() {
            const isCustom = presetSelect && presetSelect.value === 'custom';
            if (!customWrap || !customInput) {
                return;
            }

            customWrap.classList.toggle('d-none', !isCustom);
            customInput.disabled = !isCustom;
            customInput.required = isCustom;

            if (!isCustom) {
                customInput.value = '';
            }
        }

        batchModalEl.addEventListener('show.bs.modal', function (event) {
            const trigger = event.relatedTarget;
            if (!trigger) {
                return;
            }

            const examId = trigger.getAttribute('data-exam-id') || '';
            const examName = trigger.getAttribute('data-exam-name') || '';
            const examLabel = trigger.getAttribute('data-exam-label') || examName;
            const activityType = trigger.getAttribute('data-activity-type') || '';
            const timeLimit = trigger.getAttribute('data-time-limit') || '';
            const deadline = trigger.getAttribute('data-deadline') || '';
            const distributionId = trigger.getAttribute('data-distribution-id') || '';

            if (batchLabel) {
                batchLabel.textContent = examLabel + (activityType ? ' - ' + activityType : '');
            }
            if (examIdInput) {
                examIdInput.value = examId;
            }
            if (examNameInput) {
                examNameInput.value = examName;
            }
            if (activityTypeInput) {
                activityTypeInput.value = activityType;
            }
            if (timeLimitInput) {
                timeLimitInput.value = timeLimit;
            }
            if (deadlineInput) {
                deadlineInput.value = deadline;
            }
            if (distributionIdInput) {
                distributionIdInput.value = distributionId;
            }
            if (presetSelect) {
                presetSelect.value = '24h';
            }
            syncBatchCustomDeadlineState();
        });

        if (presetSelect) {
            presetSelect.addEventListener('change', syncBatchCustomDeadlineState);
        }

        syncBatchCustomDeadlineState();
    });
