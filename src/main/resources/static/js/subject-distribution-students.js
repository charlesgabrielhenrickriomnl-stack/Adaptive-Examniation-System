document.addEventListener('DOMContentLoaded', function () {
        const input = document.getElementById('distributionSearch');
        if (input) {
            input.addEventListener('input', function () {
                const query = this.value.toLowerCase().trim();
                document.querySelectorAll('#distributionStudentsTable tbody tr').forEach(row => {
                    const key = row.getAttribute('data-search-key') || '';
                    row.style.display = (!query || key.includes(query)) ? '' : 'none';
                });
            });
        }

        const reopenModalEl = document.getElementById('reopenStudentModal');
        if (reopenModalEl && reopenModalEl.parentElement !== document.body) {
            document.body.appendChild(reopenModalEl);
        }
        if (!reopenModalEl || typeof bootstrap === 'undefined') {
            return;
        }

        const reopenTitle = document.getElementById('reopenStudentModalTitle');
        const reopenStudentName = document.getElementById('reopenStudentName');
        const studentEmailInput = document.getElementById('reopenStudentEmailInput');
        const distributionIdInput = document.getElementById('reopenStudentDistributionId');
        const presetSelect = document.getElementById('reopenPreset');
        const customWrap = document.getElementById('customDeadlineWrap');
        const customInput = document.getElementById('customDeadline');

        function syncCustomDeadlineState() {
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

        reopenModalEl.addEventListener('show.bs.modal', function (event) {
            const trigger = event.relatedTarget;
            if (!trigger) {
                return;
            }

            const studentEmail = trigger.getAttribute('data-student-email') || '';
            const studentName = trigger.getAttribute('data-student-name') || 'Student';
            const distributionId = trigger.getAttribute('data-distribution-id') || '';
            const reopenLabel = trigger.getAttribute('data-reopen-label') || 'Re-open';

            if (reopenTitle) {
                reopenTitle.textContent = reopenLabel + ' Quiz';
            }
            if (reopenStudentName) {
                reopenStudentName.textContent = studentName + ' (' + studentEmail + ')';
            }
            if (studentEmailInput) {
                studentEmailInput.value = studentEmail;
            }
            if (distributionIdInput) {
                distributionIdInput.value = distributionId;
            }
            if (presetSelect) {
                presetSelect.value = '24h';
            }
            syncCustomDeadlineState();
        });

        if (presetSelect) {
            presetSelect.addEventListener('change', syncCustomDeadlineState);
        }

        syncCustomDeadlineState();
    });
