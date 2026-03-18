document.addEventListener('DOMContentLoaded', function () {
    const errorField = document.body.dataset.errorField || '';
    const showSignupHint = (document.body.dataset.showSignupHint || '').trim().toLowerCase() === 'true';
    const emailInput = document.getElementById('username');
    const passwordInput = document.getElementById('password');
    const loginErrorAlert = document.getElementById('loginErrorAlert');
    const loginSuccessAlert = document.getElementById('loginSuccessAlert');

    if (errorField === 'password' && passwordInput) {
        passwordInput.focus();
        return;
    }

    if (emailInput) {
        emailInput.focus();
    }

    if (showSignupHint && loginErrorAlert) {
        setTimeout(function () {
            loginErrorAlert.style.transition = 'opacity 0.2s ease';
            loginErrorAlert.style.opacity = '0';
            setTimeout(function () {
                if (loginErrorAlert.parentNode) {
                    loginErrorAlert.parentNode.removeChild(loginErrorAlert);
                }
            }, 220);
        }, 3000);
    }

    if (loginSuccessAlert) {
        const successText = (loginSuccessAlert.textContent || '').trim().toLowerCase();
        const isRegistrationSuccess = successText.includes('registration successful');
        if (isRegistrationSuccess) {
            setTimeout(function () {
                loginSuccessAlert.style.transition = 'opacity 0.2s ease';
                loginSuccessAlert.style.opacity = '0';
                setTimeout(function () {
                    if (loginSuccessAlert.parentNode) {
                        loginSuccessAlert.parentNode.removeChild(loginSuccessAlert);
                    }
                }, 220);
            }, 3000);
        }
    }
});
