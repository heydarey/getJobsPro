package com.getjobs.worker.match;

/**
 * 待判定的岗位信息。
 *
 * @param platform    String，必填，平台标识：boss / zhilian
 * @param keyword     String，可空，命中该岗位的搜索关键词
 * @param jobName     String，必填，岗位名称；为空时直接判定不发送
 * @param companyName String，可空，公司名称
 * @param salary      String，可空，薪资文本
 * @param location    String，可空，工作地点
 * @param description String，必填，岗位描述；为空时直接判定不发送
 */
public record JobCandidate(String platform, String keyword, String jobName, String companyName,
                           String salary, String location, String description) {
}
