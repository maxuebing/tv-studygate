package com.bing.mitvguard;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库单题与系统配置数据模型。
 */
public class QuizItem {

    private int id;
    private String subject;
    private String grade;
    private String unit;
    private String question;
    private List<String> options;
    private int correctIndex;
    private String explanation;

    /**
     * 构造方法。
     *
     * @param id           题目唯一标识
     * @param subject      学科名称
     * @param grade        年级标识
     * @param question     题干文本
     * @param options      四个选项列表
     * @param correctIndex 正确选项索引（0 至 3）
     * @param explanation  题目解析
     */
    public QuizItem(
            int id,
            String subject,
            String grade,
            String question,
            List<String> options,
            int correctIndex,
            String explanation) {
        this.id = id;
        this.subject = subject;
        this.grade = grade;
        this.question = question;
        this.options = options != null ? options : new ArrayList<String>();
        this.correctIndex = correctIndex;
        this.explanation = explanation != null ? explanation : "";
    }

    public int getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getGrade() {
        return grade;
    }

    public String getUnit() {
        return unit != null ? unit : "";
    }

    public void setUnit(String unit) {
        this.unit = unit != null ? unit : "";
    }

    public String getQuestion() {
        return question;
    }

    public List<String> getOptions() {
        return options;
    }

    public int getCorrectIndex() {
        return correctIndex;
    }

    public String getExplanation() {
        return explanation;
    }

    /**
     * 运行时全局防沉迷配置。
     */
    public static class QuizConfig {

        private int watchLimitSeconds = 600;
        private int wrongPenalty = 1;
        private int maxPendingQuestions = 5;
        private String parentPin = "1234";
        private int bypassSeconds = 3600;

        public int getWatchLimitSeconds() {
            return watchLimitSeconds;
        }

        public void setWatchLimitSeconds(int watchLimitSeconds) {
            this.watchLimitSeconds = Math.max(30, watchLimitSeconds);
        }

        public int getWrongPenalty() {
            return wrongPenalty;
        }

        public void setWrongPenalty(int wrongPenalty) {
            this.wrongPenalty = Math.max(0, Math.min(3, wrongPenalty));
        }

        public int getMaxPendingQuestions() {
            return maxPendingQuestions;
        }

        public void setMaxPendingQuestions(int maxPendingQuestions) {
            this.maxPendingQuestions = Math.max(1, Math.min(20, maxPendingQuestions));
        }

        public String getParentPin() {
            return parentPin;
        }

        public void setParentPin(String parentPin) {
            if (parentPin != null && parentPin.matches("\\d{4}")) {
                this.parentPin = parentPin;
            }
        }

        public int getBypassSeconds() {
            return bypassSeconds;
        }

        public void setBypassSeconds(int bypassSeconds) {
            this.bypassSeconds = Math.max(60, bypassSeconds);
        }
    }
}
