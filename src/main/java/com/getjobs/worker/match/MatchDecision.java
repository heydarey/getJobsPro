package com.getjobs.worker.match;

/**
 * 岗位匹配判定结果。
 *
 * @param pass     boolean，true 表示允许发送；false 表示不发送
 * @param stage    String，判定结束所在环节：信息完整性 / 名称必含词 / 名称排除词 / AI复核 / 招呼语校验 / 通过
 * @param reason   String，判定原因，展示给用户
 * @param score    Integer，可空，AI 给出的匹配分数（0~100），未走到 AI 环节时为 null
 * @param greeting String，可空，通过时实际要发送的招呼语；不通过时为 null
 */
public record MatchDecision(boolean pass, String stage, String reason, Integer score, String greeting) {

    /**
     * 构造不通过的判定结果。
     *
     * @param stage  String，必填，判定结束所在环节
     * @param reason String，必填，不通过原因
     * @param score  Integer，可空，AI 分数
     * @return MatchDecision，pass=false，greeting=null
     */
    public static MatchDecision reject(String stage, String reason, Integer score) {
        return new MatchDecision(false, stage, reason, score, null);
    }

    /** AI 不可用类原因的统一前缀，用于区分“AI 判定不匹配”和“AI 调用本身失败” */
    public static final String AI_ERROR_PREFIX = "AI 复核失败";

    /**
     * 是否因 AI 调用失败（超时、报错、返回无法解析、缺字段）而未通过。
     * 入参：无。
     *
     * @return boolean，true 表示 AI 不可用导致的拒绝，而不是岗位不匹配
     */
    public boolean aiUnavailable() {
        return !pass && reason != null && reason.startsWith(AI_ERROR_PREFIX);
    }
}
