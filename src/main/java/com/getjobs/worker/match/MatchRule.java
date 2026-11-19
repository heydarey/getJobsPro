package com.getjobs.worker.match;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位匹配规则（Boss / 智联共用，保存在 match_rule 表，只有一行）。
 * <p>
 * 发送前的判定顺序：岗位信息完整 → 岗位名称必含词 → 岗位名称排除词 → AI 严格复核（分数不低于 minScore）
 * → 招呼语校验。任一环节不通过或出错都不发送。
 */
@Data
public class MatchRule {

    /** 岗位名称必须包含其中至少一个词（不区分大小写），为空时任务拒绝启动 */
    private List<String> titleIncludeKeywords = new ArrayList<>();

    /** 岗位名称包含其中任一词即排除（不区分大小写） */
    private List<String> titleExcludeKeywords = new ArrayList<>();

    /** AI 匹配分数下限，0~100，默认 85 */
    private Integer minScore = 85;

    /** 招呼语中不允许出现的词，AI 生成或固定招呼语命中时不发送 */
    private List<String> greetingForbiddenWords = new ArrayList<>();

    /** 给 AI 的求职要求说明，例如只接受远程兼职、不接受驻场坐班 */
    private String jobRequirement = "";

    /**
     * 返回规则默认值，用于首次建表时写入。
     *
     * @return MatchRule，默认规则；字段均非空
     */
    public static MatchRule defaults() {
        MatchRule r = new MatchRule();
        r.setTitleIncludeKeywords(new ArrayList<>(List.of(
                "前端", "Web", "H5", "Vue", "React", "小程序", "uniapp", "uni-app")));
        r.setTitleExcludeKeywords(new ArrayList<>(List.of(
                "设计", "UI", "测试", "剪辑", "运营", "销售", "客服", "产品经理", "主播", "编辑",
                "顾问", "讲师", "老师", "实习", "全栈", "后端", "Java", "Python", "PHP", "Golang",
                "C++", "算法", "标注", "评测", "推广", "招聘")));
        r.setMinScore(85);
        r.setGreetingForbiddenWords(new ArrayList<>(List.of("全栈")));
        r.setJobRequirement("只找前端开发方向的远程兼职；岗位要求驻场、坐班、到岗办公或只招全职的，一律不匹配；"
                + "岗位核心工作是后端、测试、设计、运营等非前端开发的，一律不匹配。");
        return r;
    }
}
