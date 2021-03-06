package com.bobs.coord;


/**
 * Created by dokeeffe on 12/20/16.
 */
public class Target {

    private Double raDeg;
    private Double raHours;
    private Double dec;
    private String motion; //TODO enum for n,s,e,w,abort
    private Integer motionRate;
    private String type; //TODO: make enum, either sync or slew, park or unpark
    private Double guidePulseDurationMs;

    public Target() {
        //default constructor
    }

    public Target(Double raDeg, Double dec) {
        this.raDeg = raDeg;
        this.dec = dec;
    }

    public Double getRaDeg() {
        return raDeg;
    }

    public void setRaDeg(Double raDeg) {
        this.raDeg = raDeg;
    }

    public Double getRaHours() {
        return raHours;
    }

    public void setRaHours(Double raHours) {
        this.raHours = raHours;
    }

    public Double getDec() {
        return dec;
    }

    public void setDec(Double dec) {
        this.dec = dec;
    }

    public String getMotion() {
        return motion;
    }

    public void setMotion(String motion) {
        this.motion = motion;
    }

    public Integer getMotionRate() {
        return motionRate;
    }

    public void setMotionRate(Integer motionRate) {
        this.motionRate = motionRate;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Double getGuidePulseDurationMs() {
        return guidePulseDurationMs;
    }

    public void setGuidePulseDurationMs(Double guidePulseDurationMs) {
        this.guidePulseDurationMs = guidePulseDurationMs;
    }
}
