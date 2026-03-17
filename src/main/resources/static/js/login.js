document.addEventListener('DOMContentLoaded', function () {
    const errorField = document.body.dataset.errorField || '';
    const verifyResult = (document.body.dataset.verifyResult || '').trim().toLowerCase();
    const emailInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');

    const verifyModalElement = document.getElementById('verifyResultModal');
    if (verifyModalElement && verifyResult && window.bootstrap && window.bootstrap.Modal) {
        const titleElement = document.getElementById('verifyResultTitle');
        const iconElement = document.getElementById('verifyResultIcon');
        const headlineElement = document.getElementById('verifyResultHeadline');
        const copyElement = document.getElementById('verifyResultCopy');

        if (verifyResult === 'success') {
            verifyModalElement.classList.remove('verify-result-failed');
            verifyModalElement.classList.add('verify-result-success');
            if (titleElement) titleElement.textContent = 'Email Verification Complete';
            if (iconElement) iconElement.textContent = '✓';
            if (headlineElement) headlineElement.textContent = 'Email verified successfully!';
            if (copyElement) copyElement.textContent = 'You can now sign in with your teacher account.';
        } else {
            verifyModalElement.classList.remove('verify-result-success');
            verifyModalElement.classList.add('verify-result-failed');
            if (titleElement) titleElement.textContent = 'Verification Failed';
            if (iconElement) iconElement.textContent = '!';
            if (headlineElement) headlineElement.textContent = 'Verification link is invalid or expired.';
            if (copyElement) copyElement.textContent = 'Please request a new verification email and try again.';
        }

        const verifyModal = new window.bootstrap.Modal(verifyModalElement);
        verifyModal.show();
    }

    if (errorField === 'password' && passwordInput) {
        passwordInput.focus();
        return;
    }

    if (emailInput) {
        emailInput.focus();
    }
});
