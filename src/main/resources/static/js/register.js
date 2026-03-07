// Register Page JavaScript
document.addEventListener('DOMContentLoaded', function() {
    const form = document.getElementById('registerForm');
    const email = document.getElementById('email');
    const password = document.getElementById('password');
    const confirmPassword = document.getElementById('confirmPassword');
    const departmentSelect = document.getElementById('departmentName');
    const programSelect = document.getElementById('programName');
    const passwordHelp = document.getElementById('passwordHelp');

    function syncProgramOptions() {
        if (!departmentSelect || !programSelect) {
            return;
        }

        const selectedDepartment = departmentSelect.value || '';
        const programsMap = window.programsByDepartment || {};
        const programs = programsMap[selectedDepartment] || [];

        const previousValue = programSelect.value;
        programSelect.innerHTML = '';

        const placeholder = document.createElement('option');
        placeholder.value = '';
        placeholder.textContent = 'Select Program';
        programSelect.appendChild(placeholder);

        programs.forEach(program => {
            const option = document.createElement('option');
            option.value = program;
            option.textContent = program;
            programSelect.appendChild(option);
        });

        if (previousValue && programs.includes(previousValue)) {
            programSelect.value = previousValue;
        } else {
            programSelect.value = '';
        }

        programSelect.required = programs.length > 0;
    }

    if (departmentSelect) {
        departmentSelect.addEventListener('change', syncProgramOptions);
        syncProgramOptions();
    }
    
    // Password confirmation validation
    confirmPassword.addEventListener('input', function() {
        if (password.value !== confirmPassword.value) {
            passwordHelp.classList.remove('d-none');
            confirmPassword.setCustomValidity('Passwords do not match');
        } else {
            passwordHelp.classList.add('d-none');
            confirmPassword.setCustomValidity('');
        }
    });
    
    password.addEventListener('input', function() {
        if (confirmPassword.value !== '') {
            if (password.value !== confirmPassword.value) {
                passwordHelp.classList.remove('d-none');
                confirmPassword.setCustomValidity('Passwords do not match');
            } else {
                passwordHelp.classList.add('d-none');
                confirmPassword.setCustomValidity('');
            }
        }
    });
    
    // Form validation
    form.addEventListener('submit', function(e) {
        // Check password match
        if (password.value !== confirmPassword.value) {
            e.preventDefault();
            passwordHelp.classList.remove('d-none');
            confirmPassword.focus();
            return false;
        }
        
        // Validate password length
        if (password.value.length < 6) {
            e.preventDefault();
            alert('⚠️ Password must be at least 6 characters long');
            password.focus();
            return false;
        }

        if (departmentSelect && programSelect) {
            const selectedDepartment = departmentSelect.value || '';
            const programsMap = window.programsByDepartment || {};
            const programs = programsMap[selectedDepartment] || [];
            if (programs.length > 0 && !programSelect.value) {
                e.preventDefault();
                alert('⚠️ Please select a program for your department');
                programSelect.focus();
                return false;
            }
        }
        
        return true;
    });
    
    // Show password strength indicator
    password.addEventListener('input', function() {
        const strength = calculatePasswordStrength(password.value);
        // You can add a visual indicator here if needed
    });
    
    function calculatePasswordStrength(pwd) {
        let strength = 0;
        if (pwd.length >= 6) strength++;
        if (pwd.length >= 10) strength++;
        if (/[a-z]/.test(pwd) && /[A-Z]/.test(pwd)) strength++;
        if (/\d/.test(pwd)) strength++;
        if (/[^a-zA-Z0-9]/.test(pwd)) strength++;
        return strength;
    }
});
