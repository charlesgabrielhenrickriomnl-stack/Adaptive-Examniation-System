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

        const maskedToken = '••••••';

        function setOtpVisibility(codeChip, rowButton, showCode) {
            if (!codeChip) {
                return;
            }

            const actualCode = codeChip.getAttribute('data-otp-code') || '';
            codeChip.textContent = showCode ? actualCode : maskedToken;
            codeChip.classList.toggle('is-masked', !showCode);

            if (rowButton) {
                rowButton.setAttribute('data-otp-row-toggle', showCode ? 'hide' : 'show');
                rowButton.innerHTML = showCode
                    ? '<i class="bi bi-eye-slash me-1"></i>Hide'
                    : '<i class="bi bi-eye me-1"></i>Show';
            }
        }

        document.querySelectorAll('.otp-row-toggle-btn, .otp-inline-toggle-btn').forEach(button => {
            button.addEventListener('click', function () {
                const row = this.closest('tr');
                const codeChip = row ? row.querySelector('.otp-code-chip') : this.closest('.otp-inline-wrap')?.querySelector('.otp-code-chip');
                const shouldShow = this.getAttribute('data-otp-row-toggle') === 'show';
                setOtpVisibility(codeChip, this, shouldShow);
            });
        });

        document.querySelectorAll('.otp-toggle-all-btn').forEach(button => {
            button.addEventListener('click', function () {
                const showCode = this.getAttribute('data-otp-toggle-all') === 'show';
                document.querySelectorAll('.otp-code-chip').forEach(codeChip => {
                    const row = codeChip.closest('tr');
                    const rowButton = row ? row.querySelector('.otp-row-toggle-btn, .otp-inline-toggle-btn') : null;
                    setOtpVisibility(codeChip, rowButton, showCode);
                });
            });
        });

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
