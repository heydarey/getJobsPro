package com.getjobs.application.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("zhilian_config")
public class ZhilianConfigEntity {
    @TableId(type = IdType.AUTO)
    /** 主键ID */
    private Long id;

    /** 搜索关键词（逗号或括号列表，例如 "[Java,后端]" 或 "Java,后端"） */
    private String keywords;

    /** 城市（中文名或代码，单值） */
    private String cityCode;

    /** 薪资范围（中文名或代码，单值） */
    private String salary;

    /** 是否只搜索「工作性质：兼职/临时」岗位（智联搜索参数 et=1）：1 是 / 0 否；默认 1 */
    private Integer onlyPartTime;

    /** 投递后是否进入聊天发送招呼语：1 发送 / 0 不发送（只投递，平台自动附带账号默认招呼语）；默认 1 */
    private Integer sendGreeting;

    /** 投递后是否在聊天中发送图片简历（使用 Boss 页上传的图片）：1 发送 / 0 不发送；默认 1 */
    private Integer sendImgResume;

    /** 每日最多投递并打招呼的次数，0 表示不限；默认 20 */
    private Integer dailyGreetLimit;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}