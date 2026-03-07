/**
 * Student Performance Analytics JavaScript
 * Handles radar chart visualization using Chart.js
 */

document.addEventListener('DOMContentLoaded', function() {
    console.log('🚀 Initializing Student Performance Analytics...');
    
    // Initialize the radar chart
    initializeRadarChart();
    
    // Animate progress bars
    animateProgressBars();
    
    // Add smooth scrolling
    setupSmoothScrolling();
    
    // Initialize tooltips
    initializeTooltips();
});

/**
 * Initialize the radar chart with performance data
 */
function initializeRadarChart() {
    const ctx = document.getElementById('performanceRadarChart');
    
    if (!ctx) {
        console.error('❌ Radar chart canvas not found!');
        return;
    }
    
    // Check if performanceData is available (passed from Thymeleaf)
    if (typeof performanceData === 'undefined') {
        console.error('❌ Performance data not found!');
        return;
    }
    
    // Get values from performanceData
    const topicMastery = parseFloat(performanceData.topicMastery) || 0;
    const difficultyResilience = parseFloat(performanceData.difficultyResilience) || 0;
    const accuracy = parseFloat(performanceData.accuracy) || 0;
    const timeEfficiency = parseFloat(performanceData.timeEfficiency) || 0;
    const confidence = parseFloat(performanceData.confidence) || 0;
    
    console.log('📊 Chart Data:', {
        topicMastery,
        difficultyResilience,
        accuracy,
        timeEfficiency,
        confidence
    });
    
        const gradient = ctx.getContext('2d').createLinearGradient(0, 0, 0, 400);
        gradient.addColorStop(0, 'rgba(122, 16, 34, 0.45)');
        gradient.addColorStop(1, 'rgba(212, 163, 42, 0.40)');
    
    // Create the radar chart
    const radarChart = new Chart(ctx, {
        type: 'radar',
        data: {
            labels: [
                'Topic Mastery',
                'Difficulty Resilience',
                'Accuracy',
                'Time Efficiency',
                'Confidence'
            ],
            datasets: [{
                label: 'Your Performance',
                data: [
                    topicMastery,
                    difficultyResilience,
                    accuracy,
                    timeEfficiency,
                    confidence
                ],
                backgroundColor: gradient,
                    borderColor: 'rgba(122, 16, 34, 1)',
                borderWidth: 3,
                    pointBackgroundColor: 'rgba(122, 16, 34, 1)',
                pointBorderColor: '#fff',
                pointHoverBackgroundColor: '#fff',
                    pointHoverBorderColor: 'rgba(122, 16, 34, 1)',
                pointRadius: 6,
                pointHoverRadius: 8
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: {
                    display: true,
                    position: 'top',
                    labels: {
                        font: {
                            size: 14,
                            weight: 'bold'
                        },
                        color: '#333'
                    }
                },
                title: {
                    display: true,
                    text: '5-Dimension Performance Analysis',
                    font: {
                        size: 18,
                        weight: 'bold'
                    },
                        color: '#7a1022',
                    padding: 20
                },
                tooltip: {
                    backgroundColor: 'rgba(0, 0, 0, 0.8)',
                    titleColor: '#fff',
                    bodyColor: '#fff',
                    borderColor: '#7a1022',
                    borderWidth: 2,
                    padding: 12,
                    displayColors: true,
                    callbacks: {
                        label: function(context) {
                            let label = context.dataset.label || '';
                            if (label) {
                                label += ': ';
                            }
                            label += context.parsed.r.toFixed(2) + '%';
                            return label;
                        }
                    }
                }
            },
            scales: {
                r: {
                    min: 0,
                    max: 100,
                    beginAtZero: true,
                    ticks: {
                        stepSize: 20,
                        font: {
                            size: 12
                        },
                        color: '#666',
                        backdropColor: 'transparent'
                    },
                    grid: {
                        color: 'rgba(0, 0, 0, 0.1)',
                        circular: true
                    },
                    pointLabels: {
                        font: {
                            size: 13,
                            weight: '600'
                        },
                        color: '#333'
                    },
                    angleLines: {
                        color: 'rgba(0, 0, 0, 0.1)'
                    }
                }
            },
            animation: {
                duration: 1500,
                easing: 'easeInOutQuart'
            }
        }
    });
    
    console.log('✅ Radar chart initialized successfully!');
    
    // Store chart instance globally for potential updates
    window.performanceRadarChart = radarChart;
}

/**
 * Animate progress bars on page load
 */
function animateProgressBars() {
    const progressBars = document.querySelectorAll('.progress-bar');
    
    progressBars.forEach((bar, index) => {
        const targetWidth = bar.style.width;
        bar.style.width = '0%';
        
        setTimeout(() => {
            bar.style.transition = 'width 1.5s ease-in-out';
            bar.style.width = targetWidth;
        }, 300 + (index * 100));
    });
    
    console.log('✅ Progress bars animated');
}

/**
 * Setup smooth scrolling for anchor links
 */
function setupSmoothScrolling() {
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            e.preventDefault();
            const target = document.querySelector(this.getAttribute('href'));
            
            if (target) {
                target.scrollIntoView({
                    behavior: 'smooth',
                    block: 'start'
                });
            }
        });
    });
}

/**
 * Initialize Bootstrap tooltips
 */
function initializeTooltips() {
    const tooltipTriggerList = [].slice.call(
        document.querySelectorAll('[data-bs-toggle="tooltip"]')
    );
    
    tooltipTriggerList.map(function (tooltipTriggerEl) {
        return new bootstrap.Tooltip(tooltipTriggerEl);
    });
    
    console.log('✅ Tooltips initialized');
}

/**
 * Get color based on performance value
 * @param {number} value - Performance value (0-100)
 * @returns {string} Color class name
 */
function getPerformanceColor(value) {
    if (value >= 80) return 'success';
    if (value >= 60) return 'info';
    if (value >= 40) return 'warning';
    return 'danger';
}

/**
 * Format decimal number to percentage
 * @param {number} value - Decimal value (0-100)
 * @returns {string} Formatted percentage
 */
function formatPercentage(value) {
    return value.toFixed(2) + '%';
}

/**
 * Export chart as image (optional feature)
 */
function exportChartAsImage() {
    const chart = window.performanceRadarChart;
    
    if (!chart) {
        console.error('❌ Chart not found!');
        return;
    }
    
    const url = chart.toBase64Image();
    const link = document.createElement('a');
    link.href = url;
    link.download = 'performance-analytics.png';
    link.click();
    
    console.log('✅ Chart exported as image');
}

/**
 * Print performance report
 */
function printReport() {
    window.print();
}

/**
 * Share performance (optional feature for future)
 */
function sharePerformance() {
    if (navigator.share) {
        navigator.share({
            title: 'My Exam Performance',
            text: 'Check out my exam performance analysis!',
            url: window.location.href
        }).then(() => {
            console.log('✅ Successfully shared');
        }).catch((error) => {
            console.error('❌ Error sharing:', error);
        });
    } else {
        alert('Sharing not supported on this browser');
    }
}

/**
 * Calculate overall grade based on performance metrics
 * @param {Object} metrics - Performance metrics object
 * @returns {string} Grade letter
 */
function calculateGrade(metrics) {
    const average = (
        parseFloat(metrics.topicMastery) +
        parseFloat(metrics.difficultyResilience) +
        parseFloat(metrics.accuracy) +
        parseFloat(metrics.timeEfficiency) +
        parseFloat(metrics.confidence)
    ) / 5;
    
    if (average >= 90) return 'A+';
    if (average >= 85) return 'A';
    if (average >= 80) return 'A-';
    if (average >= 75) return 'B+';
    if (average >= 70) return 'B';
    if (average >= 65) return 'B-';
    if (average >= 60) return 'C+';
    if (average >= 55) return 'C';
    if (average >= 50) return 'C-';
    return 'F';
}

// Export functions for global access
window.exportChartAsImage = exportChartAsImage;
window.printReport = printReport;
window.sharePerformance = sharePerformance;

console.log('✅ Student Performance Analytics JavaScript loaded successfully!');
