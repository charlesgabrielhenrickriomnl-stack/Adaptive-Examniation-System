document.addEventListener('DOMContentLoaded', function() {
    
    // 1. Logic for Downloading the Processed Exam (Fisher-Yates/IRT)
    const downloadBtn = document.getElementById('downloadExamBtn');
    if (downloadBtn) {
        downloadBtn.addEventListener('click', function() {
            const data = document.getElementById('processedExamData').value;
            const blob = new Blob([data], { type: 'text/plain' });
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'Verified_Final_Exam.txt';
            document.body.appendChild(a);
            a.click();
            window.URL.revokeObjectURL(url);
            document.body.removeChild(a);
        });
    }
	function viewOnline() {
	    const viewer = document.getElementById('onlineViewer');
	    const list = document.getElementById('questionList');
	    
	    // In a real app, you'd fetch the session data via an AJAX call
	    // For this example, we'll assume the data is passed or we just toggle visibility
	    viewer.classList.toggle('hidden');
	    
	    if (!viewer.classList.contains('hidden')) {
	        // You can use a fetch() call here to get the JSON list from the backend
	        console.log("Teacher is viewing the shuffled exam online.");
	    }
	}
    // 2. IMPROVED Logic for Rendering the Performance Radar Chart (Random Forest)
    const radarBtn = document.getElementById('renderRadarBtn');
    const toggleChartBtn = document.getElementById('toggleChartBtn');
    const chartSection = document.getElementById('chartSection');
    let radarChart = null; // Store chart instance for updates

    function setChartVisible(visible) {
        if (!chartSection) return;
        chartSection.classList.toggle('hidden', !visible);
        if (toggleChartBtn) {
            toggleChartBtn.classList.toggle('d-none', !visible);
        }
    }
    
    if (radarBtn) {
        radarBtn.addEventListener('click', async function() {
            try {
                radarBtn.disabled = true;
                radarBtn.textContent = 'Loading...';
                
                // Get analytics data from hidden inputs (already passed from backend)
                const analytics = {
                    topicMastery: parseFloat(document.getElementById('topicMastery')?.value || 0),
                    difficultyResilience: parseFloat(document.getElementById('difficultyResilience')?.value || 0),
                    accuracy: parseFloat(document.getElementById('accuracy')?.value || 0),
                    timeEfficiency: parseFloat(document.getElementById('timeEfficiency')?.value || 0),
                    confidence: parseFloat(document.getElementById('confidence')?.value || 0),
                    performanceCategory: document.getElementById('performanceCategory')?.value || 'No Data',
                    historicalData: null // Will be null for now
                };
                
                // Reveal the chart section
                setChartVisible(true);
                
                // Get ALL metrics from backend Random Forest predictions
                const metricsData = {
                    topicMastery: analytics.topicMastery || 0,
                    difficultyResilience: analytics.difficultyResilience || 0,
                    accuracy: analytics.accuracy || 0,
                    timeEfficiency: analytics.timeEfficiency || 0,
                    confidence: analytics.confidence || 0
                };
                
                // Calculate overall performance score
                const overallScore = Object.values(metricsData).reduce((a, b) => a + b, 0) / Object.keys(metricsData).length;
                
                // Display performance summary
                displayPerformanceSummary(metricsData, overallScore, analytics.performanceCategory);
                
                // Render or update chart
                const ctx = document.getElementById('radarChartCanvas').getContext('2d');
                
                if (radarChart) {
                    // Update existing chart
                    radarChart.data.datasets[0].data = Object.values(metricsData);
                    radarChart.update();
                } else {
                    // Create new chart with enhanced styling
                    radarChart = new Chart(ctx, {
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
                                label: 'Student Analytics Profile (Random Forest)',
                                data: Object.values(metricsData),
                                fill: true,
                                backgroundColor: getPerformanceColor(overallScore, 0.2),
                                borderColor: getPerformanceColor(overallScore, 1),
                                pointBackgroundColor: getPerformanceColor(overallScore, 1),
                                pointBorderColor: '#fff',
                                pointHoverBackgroundColor: '#fff',
                                pointHoverBorderColor: getPerformanceColor(overallScore, 1),
                                pointRadius: 5,
                                pointHoverRadius: 7
                            }]
                        },
                        options: {
                            responsive: true,
                            maintainAspectRatio: true,
                            elements: { 
                                line: { 
                                    borderWidth: 3,
                                    tension: 0.2
                                } 
                            },
                            scales: {
                                r: {
                                    angleLines: { 
                                        display: true,
                                        color: 'rgba(0, 0, 0, 0.1)'
                                    },
                                    suggestedMin: 0,
                                    suggestedMax: 100,
                                    ticks: {
                                        stepSize: 20,
                                        callback: function(value) {
                                            return value + '%';
                                        }
                                    },
                                    pointLabels: {
                                        font: {
                                            size: 12,
                                            weight: 'bold'
                                        }
                                    }
                                }
                            },
                            plugins: {
                                legend: {
                                    display: true,
                                    position: 'top'
                                },
                                tooltip: {
                                    callbacks: {
                                        label: function(context) {
                                            return context.label + ': ' + context.parsed.r.toFixed(2) + '%';
                                        }
                                    }
                                }
                            },
                            animation: {
                                duration: 1500,
                                easing: 'easeInOutQuart'
                            }
                        }
                    });
                }
                
                // Add comparison chart if historical data exists
                if (analytics.historicalData && analytics.historicalData.dates) {
                    renderComparisonChart(analytics.historicalData);
                }
                
                // Smooth scroll to chart
                if (chartSection) {
                    window.scrollTo({ 
                        top: chartSection.offsetTop - 100, 
                        behavior: 'smooth' 
                    });
                }
                
                radarBtn.textContent = '📊 View Detailed Performance Chart';
                radarBtn.disabled = false;
                
            } catch (error) {
                console.error('Error rendering analytics:', error);
                alert('Failed to load student analytics. Please try again.');
                radarBtn.disabled = false;
                radarBtn.textContent = '📊 View Detailed Performance Chart';
            }
        });
    }

    if (toggleChartBtn) {
        toggleChartBtn.addEventListener('click', function() {
            const isVisible = chartSection && !chartSection.classList.contains('hidden');
            setChartVisible(!isVisible);
        });
    }
    
    // Helper function: Get color based on performance
    function getPerformanceColor(score, alpha) {
        if (score >= 80) {
            return `rgba(25, 135, 84, ${alpha})`; // Green - Excellent
        } else if (score >= 60) {
            return `rgba(255, 193, 7, ${alpha})`; // Yellow - Good
        } else if (score >= 40) {
            return `rgba(255, 152, 0, ${alpha})`; // Orange - Fair
        } else {
            return `rgba(220, 53, 69, ${alpha})`; // Red - Needs Improvement
        }
    }
    
    // Display performance summary with insights
    function displayPerformanceSummary(metrics, overallScore, category) {
        const summaryDiv = document.getElementById('performanceSummary') || createSummaryDiv();
        
        const performanceLevel = category || (overallScore >= 80 ? 'Excellent' : 
                                overallScore >= 60 ? 'Good' : 
                                overallScore >= 40 ? 'Fair' : 'Needs Improvement');
        
        // Find strengths and weaknesses
        const sortedMetrics = Object.entries(metrics).sort((a, b) => b[1] - a[1]);
        const strengths = sortedMetrics.slice(0, 2).map(m => m[0]);
        const weaknesses = sortedMetrics.slice(-2).map(m => m[0]);
        
        summaryDiv.innerHTML = `
            <div class="alert alert-${getAlertClass(overallScore)}" role="alert">
                <h4 class="alert-heading">Performance Summary</h4>
                <p><strong>Overall Score:</strong> ${overallScore.toFixed(2)}% - ${performanceLevel}</p>
                <hr>
                <p class="mb-0">
                    <strong>Strengths:</strong> ${formatMetricNames(strengths).join(', ')}<br>
                    <strong>Areas for Improvement:</strong> ${formatMetricNames(weaknesses).join(', ')}
                </p>
            </div>
        `;
    }
    
    function createSummaryDiv() {
        const div = document.createElement('div');
        div.id = 'performanceSummary';
        div.className = 'mt-3 mb-3';
        const chartSection = document.getElementById('chartSection');
        const canvas = document.getElementById('radarChartCanvas');
        if (chartSection && canvas) {
            chartSection.insertBefore(div, canvas);
        }
        return div;
    }
    
    function getAlertClass(score) {
        if (score >= 80) return 'success';
        if (score >= 60) return 'info';
        if (score >= 40) return 'warning';
        return 'danger';
    }
    
    function formatMetricNames(names) {
        return names.map(name => name.replace(/([A-Z])/g, ' $1').trim());
    }
    
    // Render comparison chart for historical performance
    function renderComparisonChart(historicalData) {
        const comparisonCanvas = document.getElementById('comparisonChart');
        if (!comparisonCanvas) {
            // Create canvas if it doesn't exist
            const chartSection = document.getElementById('chartSection');
            if (chartSection) {
                const canvas = document.createElement('canvas');
                canvas.id = 'comparisonChart';
                canvas.width = 400;
                canvas.height = 200;
                canvas.className = 'mt-4';
                chartSection.appendChild(canvas);
                renderHistoricalChart(canvas, historicalData);
            }
        } else {
            renderHistoricalChart(comparisonCanvas, historicalData);
        }
    }
    
    function renderHistoricalChart(canvas, historicalData) {
        const ctx = canvas.getContext('2d');
        new Chart(ctx, {
            type: 'line',
            data: {
                labels: historicalData.dates,
                datasets: [{
                    label: 'Performance Trend',
                    data: historicalData.scores,
                    borderColor: 'rgb(75, 192, 192)',
                    backgroundColor: 'rgba(75, 192, 192, 0.1)',
                    tension: 0.4,
                    fill: true
                }]
            },
            options: {
                responsive: true,
                plugins: {
                    title: {
                        display: true,
                        text: 'Performance Over Time'
                    }
                },
                scales: {
                    y: {
                        beginAtZero: true,
                        max: 100
                    }
                }
            }
        });
    }
});