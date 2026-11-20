package com.getjobs.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boss 风控提醒：投递中触发平台安全验证或访问受限时写入一条提醒，
 * 管理员在 Boss 配置页确认“我已处理”之前，不允许再启动 Boss 投递。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossRiskAlertService {

    private final DataSource dataSource;

    /** 建表：boss_risk_alert，handled_at 为空表示未处理 */
    @jakarta.annotation.PostConstruct
    public void ensureTable() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS boss_risk_alert ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, alert_type TEXT NOT NULL, page_url TEXT, "
                    + "keyword TEXT, job_name TEXT, detail TEXT, created_at TEXT NOT NULL, handled_at TEXT)");
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 Boss 风控提醒表", e);
        }
    }

    /**
     * 记录一次风控触发。
     *
     * @param alertType String，必填，风控类型：访问受限 / 安全验证 / 接口异常
     * @param pageUrl   String，可空，触发时的页面或接口地址
     * @param keyword   String，可空，当时的搜索关键词
     * @param jobName   String，可空，当时处理的岗位
     * @param detail    String，可空，补充说明
     */
    public void record(String alertType, String pageUrl, String keyword, String jobName, String detail) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO boss_risk_alert "
                     + "(alert_type, page_url, keyword, job_name, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, alertType);
            ps.setString(2, pageUrl);
            ps.setString(3, keyword);
            ps.setString(4, jobName);
            ps.setString(5, detail);
            ps.setString(6, LocalDateTime.now().withNano(0).toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("记录 Boss 风控提醒失败：{}", e.getMessage());
        }
    }

    /**
     * 取最近一条未处理的风控提醒。
     * 入参：无。
     *
     * @return Map，id:Long、alertType:String、pageUrl:String、keyword:String、jobName:String、detail:String、
     * createdAt:String、unhandledCount:Integer；没有未处理提醒时为 null。读库失败时按存在提醒返回，宁可不启动
     */
    public Map<String, Object> getActiveAlert() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, alert_type, page_url, keyword, job_name, detail, "
                     + "created_at, (SELECT COUNT(*) FROM boss_risk_alert WHERE handled_at IS NULL) AS cnt "
                     + "FROM boss_risk_alert WHERE handled_at IS NULL ORDER BY id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("alertType", rs.getString("alert_type"));
            m.put("pageUrl", rs.getString("page_url"));
            m.put("keyword", rs.getString("keyword"));
            m.put("jobName", rs.getString("job_name"));
            m.put("detail", rs.getString("detail"));
            m.put("createdAt", rs.getString("created_at"));
            m.put("unhandledCount", rs.getInt("cnt"));
            return m;
        } catch (Exception e) {
            log.error("读取 Boss 风控提醒失败，按存在未处理提醒处理：{}", e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alertType", "未知");
            m.put("detail", "读取风控提醒失败：" + e.getMessage());
            m.put("unhandledCount", 1);
            return m;
        }
    }

    /**
     * 管理员确认已处理：把全部未处理提醒标记为已处理。
     * 入参：无。
     *
     * @return int，本次标记的条数
     */
    public int acknowledge() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE boss_risk_alert SET handled_at = ? WHERE handled_at IS NULL")) {
            ps.setString(1, LocalDateTime.now().withNano(0).toString());
            int n = ps.executeUpdate();
            log.info("管理员确认已处理 Boss 风控提醒 {} 条", n);
            return n;
        } catch (Exception e) {
            throw new IllegalStateException("确认风控提醒失败：" + e.getMessage(), e);
        }
    }
}
