package com.example.demo.service.anomaly;

public class ZScoreResult {

    public enum Status { OK, ANOMALY, NOT_FITTED, CONSTANT }

    private final Status status;
    private final double mu;
    private final double sigma;
    private final double z;

    private ZScoreResult(Status status, double mu, double sigma, double z) {
        this.status = status;
        this.mu = mu;
        this.sigma = sigma;
        this.z = z;
    }

    public static ZScoreResult ok(double mu, double sigma, double z) {
        return new ZScoreResult(Status.OK, mu, sigma, z);
    }

    public static ZScoreResult anomaly(double mu, double sigma, double z) {
        return new ZScoreResult(Status.ANOMALY, mu, sigma, z);
    }

    public static ZScoreResult notFitted() {
        return new ZScoreResult(Status.NOT_FITTED, Double.NaN, Double.NaN, Double.NaN);
    }

    public static ZScoreResult constant(double mu) {
        return new ZScoreResult(Status.CONSTANT, mu, 0.0, 0.0);
    }

    public Status getStatus() { return status; }
    public boolean isAnomaly() { return status == Status.ANOMALY; }
    public boolean isFitted()  { return status != Status.NOT_FITTED; }
    public double getMu()      { return mu; }
    public double getSigma()   { return sigma; }
    public double getZ()       { return z; }
}
