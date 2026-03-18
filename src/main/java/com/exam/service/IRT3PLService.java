package com.exam.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

/**
 * IRT 3PL (Three-Parameter Logistic) Model Service
 * 
 * The 3PL model calculates the probability of a correct response based on:
 * P(θ) = c + (1 - c) / (1 + e^(-a(θ - b)))
 * 
 * Where:
 * - θ (theta): Student ability level
 * - a: Item discrimination parameter (how well it differentiates abilities)
 * - b: Item difficulty parameter (ability level for 50% success)
 * - c: Pseudo-guessing parameter (probability of guessing correctly)
 */
@Service
public class IRT3PLService {

    /**
     * Item parameters for IRT 3PL model
     */
    public static class ItemParameters {
        private double discrimination; // a parameter
        private double difficulty;     // b parameter
        private double guessing;       // c parameter
        
        public ItemParameters(double discrimination, double difficulty, double guessing) {
            this.discrimination = discrimination;
            this.difficulty = difficulty;
            this.guessing = guessing;
        }
        
        public double getDiscrimination() { return discrimination; }
        public double getDifficulty() { return difficulty; }
        public double getGuessing() { return guessing; }
        
        public void setDiscrimination(double discrimination) { this.discrimination = discrimination; }
        public void setDifficulty(double difficulty) { this.difficulty = difficulty; }
        public void setGuessing(double guessing) { this.guessing = guessing; }
    }
    
    /**
     * Student ability estimation result
     */
    public static class AbilityEstimate {
        private double theta;           // Estimated ability
        private double standardError;   // Standard error of estimation
        private int itemsAnswered;      // Number of items answered
        private int correctAnswers;     // Number correct
        
        public AbilityEstimate(double theta, double standardError, int itemsAnswered, int correctAnswers) {
            this.theta = theta;
            this.standardError = standardError;
            this.itemsAnswered = itemsAnswered;
            this.correctAnswers = correctAnswers;
        }
        
        public double getTheta() { return theta; }
        public double getStandardError() { return standardError; }
        public int getItemsAnswered() { return itemsAnswered; }
        public int getCorrectAnswers() { return correctAnswers; }
    }

    /**
     * Calculate probability of correct response using 3PL model
     * P(θ) = c + (1 - c) / (1 + e^(-a(θ - b)))
     */
    public double calculateProbability(double theta, ItemParameters params) {
        double a = params.getDiscrimination();
        double b = params.getDifficulty();
        double c = params.getGuessing();
        
        double exponent = -a * (theta - b);
        double probability = c + (1 - c) / (1 + Math.exp(exponent));
        
        return probability;
    }
    
    /**
     * Estimate student ability (theta) using Maximum Likelihood Estimation (MLE)
     * with Newton-Raphson method
     */
    public AbilityEstimate estimateAbility(List<Boolean> responses, List<ItemParameters> itemParams) {
        if (responses.isEmpty() || itemParams.isEmpty()) {
            return new AbilityEstimate(0.0, 999.0, 0, 0);
        }
        
        // Initial theta estimate (start at 0 - average ability)
        double theta = 0.0;
        double previousTheta;
        int iterations = 0;
        int maxIterations = 50;
        double convergenceCriterion = 0.001;
        
        // Count correct answers
        int correctAnswers = 0;
        for (Boolean response : responses) {
            if (response) correctAnswers++;
        }
        
        // Newton-Raphson iteration
        do {
            previousTheta = theta;
            
            // Calculate first derivative (score function)
            double firstDerivative = 0.0;
            // Calculate second derivative (information function)
            double secondDerivative = 0.0;
            
            for (int i = 0; i < responses.size(); i++) {
                ItemParameters params = itemParams.get(i);
                double a = params.getDiscrimination();
                double c = params.getGuessing();
                
                double prob = calculateProbability(theta, params);
                double response = responses.get(i) ? 1.0 : 0.0;
                
                // P* = (P - c) / (1 - c)
                double pStar = (prob - c) / (1 - c);
                
                // First derivative: sum of a * (P* - P*²) * (u - P) / (P * (1 - P))
                double numerator = response - prob;
                double denominator = prob * (1 - prob);
                if (denominator > 0.0001) { // Avoid division by zero
                    firstDerivative += a * pStar * (1 - pStar) * numerator / denominator;
                }
                
                // Second derivative (Fisher Information)
                double info = a * a * pStar * (1 - pStar) * ((1 - c) * (1 - c));
                secondDerivative -= info;
            }
            
            // Newton-Raphson update: θ_new = θ_old - f'(θ) / f''(θ)
            if (Math.abs(secondDerivative) > 0.0001) {
                theta = previousTheta - (firstDerivative / secondDerivative);
            }
            
            // Constrain theta to reasonable range [-4, 4]
            theta = Math.max(-4.0, Math.min(4.0, theta));
            
            iterations++;
            
        } while (Math.abs(theta - previousTheta) > convergenceCriterion && iterations < maxIterations);
        
        // Calculate standard error (inverse square root of information)
        double information = calculateInformation(theta, itemParams);
        double standardError = information > 0 ? 1.0 / Math.sqrt(information) : 999.0;
        
        return new AbilityEstimate(theta, standardError, responses.size(), correctAnswers);
    }
    
    /**
     * Calculate Fisher Information at a given ability level
     */
    private double calculateInformation(double theta, List<ItemParameters> itemParams) {
        double information = 0.0;
        
        for (ItemParameters params : itemParams) {
            double a = params.getDiscrimination();
            double c = params.getGuessing();
            double prob = calculateProbability(theta, params);
            
            double pStar = (prob - c) / (1 - c);
            double itemInfo = a * a * pStar * (1 - pStar) * ((1 - c) * (1 - c));
            
            information += itemInfo;
        }
        
        return information;
    }
    
    /**
     * Generate default item parameters for questions
     * In practice, these would be calibrated from pilot testing
     */
    public List<ItemParameters> generateDefaultItemParameters(int numQuestions) {
        List<ItemParameters> params = new ArrayList<>();
        Random random = new Random();
        
        for (int i = 0; i < numQuestions; i++) {
            // Discrimination: typically 0.5 to 2.5 (higher = better discrimination)
            double a = 0.8 + random.nextDouble() * 1.2; // Range: 0.8 to 2.0
            
            // Difficulty: typically -3 to 3 (0 = average difficulty)
            double b = -1.5 + random.nextDouble() * 3.0; // Range: -1.5 to 1.5
            
            // Guessing: typically 0.15 to 0.25 for 4-option MC questions
            double c = 0.15 + random.nextDouble() * 0.10; // Range: 0.15 to 0.25
            
            params.add(new ItemParameters(a, b, c));
        }
        
        return params;
    }
    
    /**
     * Estimate item parameters based on student responses (simplified calibration)
     * In production, use more sophisticated methods like MMLE or Bayesian estimation
     */
    public List<ItemParameters> calibrateItems(List<List<Boolean>> allResponses) {
        if (allResponses.isEmpty()) {
            return new ArrayList<>();
        }
        
        int numItems = allResponses.get(0).size();
        List<ItemParameters> params = new ArrayList<>();
        
        for (int itemIdx = 0; itemIdx < numItems; itemIdx++) {
            // Calculate p-value (proportion correct)
            int correct = 0;
            int total = 0;
            
            for (List<Boolean> responses : allResponses) {
                if (itemIdx < responses.size()) {
                    if (responses.get(itemIdx)) correct++;
                    total++;
                }
            }
            
            double pValue = total > 0 ? (double) correct / total : 0.5;
            
            // Estimate difficulty from p-value
            // Higher p-value = easier item = lower difficulty
            double b = -Math.log(pValue / (1 - pValue + 0.001));
            b = Math.max(-3.0, Math.min(3.0, b));
            
            // Use default discrimination and guessing
            double a = 1.0; // Default discrimination
            double c = 0.20; // Default guessing (20% for 5 choices, 25% for 4 choices)
            
            params.add(new ItemParameters(a, b, c));
        }
        
        return params;
    }
    
    /**
     * Select next best item for adaptive testing
     * Returns the index of the item with maximum information at current ability
     */
    public int selectNextItem(double currentTheta, List<ItemParameters> availableItems, Set<Integer> usedIndices) {
        int bestIndex = -1;
        double maxInformation = -1.0;
        final double infoTolerance = 1e-9;
        List<Integer> tiedBest = new ArrayList<>();
        
        for (int i = 0; i < availableItems.size(); i++) {
            if (usedIndices.contains(i)) continue;
            
            ItemParameters params = availableItems.get(i);
            double a = params.getDiscrimination();
            double c = params.getGuessing();
            double prob = calculateProbability(currentTheta, params);
            
            double pStar = (prob - c) / (1 - c);
            double information = a * a * pStar * (1 - pStar) * ((1 - c) * (1 - c));
            
            if (information > maxInformation + infoTolerance) {
                maxInformation = information;
                bestIndex = i;
                tiedBest.clear();
                tiedBest.add(i);
            } else if (Math.abs(information - maxInformation) <= infoTolerance) {
                tiedBest.add(i);
            }
        }

        if (!tiedBest.isEmpty()) {
            return tiedBest.get(ThreadLocalRandom.current().nextInt(tiedBest.size()));
        }
        
        return bestIndex;
    }
    
    /**
     * Convert theta to a scaled score (e.g., 200-800 scale like SAT)
     */
    public int thetaToScaledScore(double theta, int mean, int sd) {
        return (int) Math.round(mean + (theta * sd));
    }
    
    /**
     * Convert scaled score back to theta
     */
    public double scaledScoreToTheta(int score, int mean, int sd) {
        return (double) (score - mean) / sd;
    }
}
