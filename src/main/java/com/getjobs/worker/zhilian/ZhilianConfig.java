package com.getjobs.worker.zhilian;

import com.getjobs.worker.utils.JobUtils;
import lombok.Data;
import lombok.SneakyThrows;

import java.util.List;
import java.util.Objects;

/**
 * @author loks666
 * 项目链接: <a href="https://github.com/loks666/get_jobs">https://github.com/loks666/get_jobs</a>
 */
@Data
public class ZhilianConfig {
    /**
     * 搜索关键词列表
     */
    private List<String> keywords;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 薪资范围
     */
    private String salary;

    /** 是否只搜索兼职/临时岗位，默认 true */
    private Boolean onlyPartTime = true;

    /** 投递后是否进入聊天发送招呼语；智联任务侧强制关闭（只做默认立即投递） */
    private Boolean sendGreeting = true;

    /** 投递后是否在聊天中发送图片简历；智联强制关闭（简历图含二维码会被平台拦截） */
    private Boolean sendImgResume = true;

    /** 每日最多投递并打招呼的次数，0 表示不限，默认 20 */
    private Integer dailyGreetLimit = 20;

    /** 固定招呼语（与 Boss 共用，取自 Boss 配置），可空 */
    private String sayHi;

    /** 是否使用 AI 按岗位调整招呼语（与 Boss 共用「AI 配置」页开关），默认 false */
    private Boolean enableAi = false;


    // 注意：已改为在 ZhilianJobService 中通过 ConfigService 构建配置
    // 保留空的 init 以兼容旧调用，但建议不要再使用
    @SneakyThrows
    public static ZhilianConfig init() {
        throw new UnsupportedOperationException("请在 ZhilianJobService 中通过 ConfigService 构建配置");
    }

}
