package com.laphuth.moodify.dto.emotion;

import jakarta.validation.constraints.NotBlank;

public class EmotionPredictRequest {

    @NotBlank(message = "Text cannot be blank")
    private String text;

    private String strategy = "empathy";

    private Integer limit = 20;

    public EmotionPredictRequest() {
    }

    public EmotionPredictRequest(String text, String strategy, Integer limit) {
        this.text = text;
        this.strategy = strategy != null ? strategy : "empathy";
        this.limit = limit != null ? limit : 20;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
