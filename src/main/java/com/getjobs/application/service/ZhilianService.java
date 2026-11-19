package com.getjobs.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.getjobs.application.entity.ZhilianConfigEntity;
import com.getjobs.application.entity.ZhilianOptionEntity;
import com.getjobs.application.entity.ZhilianJobDataEntity;
import com.getjobs.application.mapper.ZhilianConfigMapper;
import com.getjobs.application.mapper.ZhilianOptionMapper;
import com.getjobs.application.mapper.ZhilianJobDataMapper;
import com.getjobs.worker.zhilian.ZhilianConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ZhilianService {
    private final ZhilianConfigMapper zhilianConfigMapper;
    private final ZhilianOptionMapper zhilianOptionMapper;
    private final ZhilianJobDataMapper zhilianJobDataMapper;
    private final DataSource dataSource;

    /** 获取第一条配置（通常只有一条） */
    public ZhilianConfigEntity getFirstConfig() {
        QueryWrapper<ZhilianConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.last("LIMIT 1");
        return zhilianConfigMapper.selectOne(wrapper);
    }

    /** 从专表构建 ZhilianConfig */
    public ZhilianConfig loadZhilianConfig() {
        ZhilianConfigEntity entity = getFirstConfig();
        ZhilianConfig config = new ZhilianConfig();
        if (entity == null) {
            log.warn("zhilian_config 表为空，使用默认空配置");
            config.setKeywords(new ArrayList<>());
            config.setCityCode("0");
            config.setSalary("0");
            return config;
        }

        // 关键词解析：支持逗号或括号列表
        config.setKeywords(parseListString(entity.getKeywords()));

        // 城市：中文名映射到代码；缺省或“不限”映射为 0
        String city = safeTrim(entity.getCityCode());
        if (city == null || city.isEmpty() || "不限".equals(city)) {
            config.setCityCode("0");
        } else if (city.chars().allMatch(Character::isDigit)) {
            config.setCityCode(city);
        } else {
            String code = getCodeByTypeAndName("city", city);
            if (code == null) {
                log.warn("智联配置中的城市 {} 未在 zhilian_option 表中找到，回退为 0", city);
                config.setCityCode("0");
            } else {
                config.setCityCode(code);
            }
        }

        // 薪资：缺省或“不限”映射为 0，其它保持原值
        String salary = safeTrim(entity.getSalary());
        if (salary == null || salary.isEmpty() || "不限".equals(salary)) {
            config.setSalary("0");
        } else {
            config.setSalary(salary);
        }
        // 投递后行为：未设置时按默认值（发送招呼语、发送图片、每日 20 次）
        config.setOnlyPartTime(entity.getOnlyPartTime() == null || entity.getOnlyPartTime() != 0);
        config.setSendGreeting(entity.getSendGreeting() == null || entity.getSendGreeting() != 0);
        config.setSendImgResume(entity.getSendImgResume() == null || entity.getSendImgResume() != 0);
        config.setDailyGreetLimit(entity.getDailyGreetLimit() == null ? 20 : Math.max(0, entity.getDailyGreetLimit()));
        return config;
    }

    public List<String> parseListString(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String s = raw.trim().replace('，', ',');
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        if (s.trim().isEmpty()) return new ArrayList<>();
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .map(this::stripWrapperQuotes)
                .filter(str -> !str.isEmpty())
                .collect(Collectors.toList());
    }

    private String safeTrim(String s) { return s == null ? null : s.trim(); }

    private String stripWrapperQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 选择性更新：若传入 ID 则按 ID 更新；否则更新第一条记录（不存在则插入）
     */
    public ZhilianConfigEntity updateConfig(ZhilianConfigEntity config) {
        if (config == null) return null;
        if (config.getId() != null) {
            zhilianConfigMapper.updateById(config);
            return zhilianConfigMapper.selectById(config.getId());
        }
        return saveOrUpdateFirstSelective(config);
    }

    /**
     * 保存或选择性更新第一条记录（仅覆盖非空字段）
     */
    public ZhilianConfigEntity saveOrUpdateFirstSelective(ZhilianConfigEntity incoming) {
        ZhilianConfigEntity first = getFirstConfig();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (first == null) {
            ZhilianConfigEntity toInsert = new ZhilianConfigEntity();
            toInsert.setKeywords(incoming.getKeywords());
            toInsert.setCityCode(incoming.getCityCode());
            toInsert.setSalary(incoming.getSalary());
            toInsert.setOnlyPartTime(incoming.getOnlyPartTime());
            toInsert.setSendGreeting(incoming.getSendGreeting());
            toInsert.setSendImgResume(incoming.getSendImgResume());
            toInsert.setDailyGreetLimit(incoming.getDailyGreetLimit());
            toInsert.setCreatedAt(now);
            toInsert.setUpdatedAt(now);
            zhilianConfigMapper.insert(toInsert);
            return getFirstConfig();
        } else {
            ZhilianConfigEntity toUpdate = new ZhilianConfigEntity();
            toUpdate.setId(first.getId());
            if (incoming.getKeywords() != null) toUpdate.setKeywords(incoming.getKeywords());
            if (incoming.getCityCode() != null) toUpdate.setCityCode(incoming.getCityCode());
            if (incoming.getSalary() != null) toUpdate.setSalary(incoming.getSalary());
            if (incoming.getOnlyPartTime() != null) toUpdate.setOnlyPartTime(incoming.getOnlyPartTime() == 0 ? 0 : 1);
            if (incoming.getSendGreeting() != null) toUpdate.setSendGreeting(incoming.getSendGreeting() == 0 ? 0 : 1);
            if (incoming.getSendImgResume() != null) toUpdate.setSendImgResume(incoming.getSendImgResume() == 0 ? 0 : 1);
            if (incoming.getDailyGreetLimit() != null) toUpdate.setDailyGreetLimit(Math.max(0, incoming.getDailyGreetLimit()));
            toUpdate.setCreatedAt(first.getCreatedAt());
            toUpdate.setUpdatedAt(now);
            zhilianConfigMapper.updateById(toUpdate);
            return zhilianConfigMapper.selectById(first.getId());
        }
    }

    /**
     * 从 Boss 配置同步关键词与城市到智联配置；薪资清空为不限（兼职岗位不按月薪档筛选）。
     * Boss 的“全国”映射为智联全国代码 489，并在城市选项中补充“全国”。
     *
     * @param bossKeywords String，可空，Boss 关键词（JSON 数组文本）；为空时不同步关键词
     * @param bossCity     String，可空，Boss 城市名称（如“全国”“上海”）
     * @return Map，keywords:String、cityCode:String、cityName:String、message:String
     */
    public java.util.Map<String, String> syncFromBoss(String bossKeywords, String bossCity) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        ZhilianConfigEntity incoming = new ZhilianConfigEntity();
        if (bossKeywords != null && !bossKeywords.isBlank()) {
            incoming.setKeywords(bossKeywords);
        }
        String city = bossCity == null ? "" : bossCity.trim();
        if (city.startsWith("[") && city.endsWith("]")) {
            List<String> cities = parseListString(city);
            city = cities.isEmpty() ? "" : cities.get(0);
        }
        String cityName;
        if (city.isEmpty() || "全国".equals(city) || "不限".equals(city) || "100010000".equals(city)) {
            ensureNationwideOption();
            cityName = "全国";
        } else if (getCodeByTypeAndName("city", city) != null) {
            cityName = city;
        } else {
            ensureNationwideOption();
            cityName = "全国";
            result.put("message", "Boss 城市“" + city + "”在智联城市选项中不存在，已改为全国");
        }
        incoming.setCityCode(cityName);
        incoming.setSalary("");
        ZhilianConfigEntity saved = saveOrUpdateFirstSelective(incoming);
        result.putIfAbsent("message", "已从 Boss 同步关键词与城市");
        result.put("keywords", saved.getKeywords());
        result.put("cityName", cityName);
        result.put("cityCode", getCodeByTypeAndName("city", cityName));
        return result;
    }

    /**
     * 确保智联城市选项中存在“全国”（代码 489）。
     * 入参：无。出参：无。
     */
    private void ensureNationwideOption() {
        if (getCodeByTypeAndName("city", "全国") != null) return;
        ZhilianOptionEntity e = new ZhilianOptionEntity();
        e.setType("city");
        e.setName("全国");
        e.setCode("489");
        e.setSortOrder(0);
        e.setCreatedAt(java.time.LocalDateTime.now());
        e.setUpdatedAt(java.time.LocalDateTime.now());
        zhilianOptionMapper.insert(e);
        log.info("已为智联城市选项补充“全国”（489）");
    }

    // ========== Option 辅助 ==========

    public List<ZhilianOptionEntity> getOptionsByType(String type) {
        return zhilianOptionMapper.selectList(
                new QueryWrapper<ZhilianOptionEntity>()
                        .eq("type", type)
                        .orderByAsc("sort_order")
        );
    }

    public ZhilianOptionEntity getOptionByTypeAndCode(String type, String code) {
        return zhilianOptionMapper.selectOne(
                new QueryWrapper<ZhilianOptionEntity>()
                        .eq("type", type)
                        .eq("code", code)
                        .last("LIMIT 1")
        );
    }

    public String getCodeByTypeAndName(String type, String name) {
        ZhilianOptionEntity e = zhilianOptionMapper.selectOne(
                new QueryWrapper<ZhilianOptionEntity>()
                        .eq("type", type)
                        .eq("name", name)
                        .last("LIMIT 1")
        );
        return e == null ? null : e.getCode();
    }

    public String getNameByTypeAndCode(String type, String code) {
        ZhilianOptionEntity e = getOptionByTypeAndCode(type, code);
        return e == null ? null : e.getName();
    }

    // ==================== 数据表初始化与数据操作 ====================

    @PostConstruct
    public void ensureZhilianDataTableExists() {
        String createSql = "CREATE TABLE IF NOT EXISTS zhilian_data (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " job_id VARCHAR(64)," +
                " job_title VARCHAR(200)," +
                " job_link VARCHAR(300)," +
                " salary VARCHAR(100)," +
                " location VARCHAR(100)," +
                " experience VARCHAR(100)," +
                " degree VARCHAR(100)," +
                " company_name VARCHAR(200)," +
                " delivery_status VARCHAR(20) DEFAULT '未投递'," +
                " create_time DATETIME," +
                " update_time DATETIME" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createSql);
            log.info("确保 zhilian_data 表已存在");
            // 投递记录补充“匹配判定原因”列
            addColumnIfMissing(stmt, "zhilian_data", "match_reason", "TEXT");
            // 智联配置补充投递后行为列
            addColumnIfMissing(stmt, "zhilian_config", "only_part_time", "INTEGER DEFAULT 1");
            addColumnIfMissing(stmt, "zhilian_config", "send_greeting", "INTEGER DEFAULT 1");
            addColumnIfMissing(stmt, "zhilian_config", "send_img_resume", "INTEGER DEFAULT 1");
            addColumnIfMissing(stmt, "zhilian_config", "daily_greet_limit", "INTEGER DEFAULT 20");
            // 每日打招呼流水，用于每日上限统计
            stmt.execute("CREATE TABLE IF NOT EXISTS zhilian_greet_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, greet_date TEXT NOT NULL, job_id TEXT, "
                    + "job_title TEXT, company_name TEXT, created_at TEXT)");
        } catch (Exception e) {
            log.warn("初始化智联数据表失败: {}", e.getMessage());
        }
    }

    /**
     * 表中缺少某列时补充该列，已存在时不做任何改动。
     *
     * @param stmt       Statement，必填，可用的语句对象
     * @param table      String，必填，表名
     * @param column     String，必填，列名
     * @param definition String，必填，列定义，例如 "TEXT"、"INTEGER DEFAULT 1"
     * @throws java.sql.SQLException 执行失败时抛出
     */
    private static void addColumnIfMissing(Statement stmt, String table, String column, String definition)
            throws java.sql.SQLException {
        java.util.Set<String> cols = new java.util.HashSet<>();
        try (java.sql.ResultSet rs = stmt.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        if (!cols.isEmpty() && !cols.contains(column)) {
            stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("已为 {} 添加 {} 列", table, column);
        }
    }

    /**
     * 写入或更新一条岗位记录：job_id 不存在时插入，已存在时只补充空缺的基础信息，不改动投递状态。
     *
     * @param entity ZhilianJobDataEntity，必填，jobId 不能为空
     */
    public void upsertJob(ZhilianJobDataEntity entity) {
        if (entity == null || entity.getJobId() == null || entity.getJobId().isBlank()) return;
        if (!existsByJobId(entity.getJobId())) {
            insertJob(entity);
        }
    }

    /**
     * 更新岗位的投递状态与匹配判定原因。已投递的记录不会被改为其他状态。
     *
     * @param jobId  String，必填，智联岗位编号；为空时不更新
     * @param status String，必填，投递状态：已投递 / 已过滤 / 未投递 / 投递失败
     * @param reason String，可空，判定原因，最多保留 500 字
     */
    public void updateMatchResult(String jobId, String status, String reason) {
        if (jobId == null || jobId.isBlank() || status == null) return;
        String r = reason == null ? null : (reason.length() > 500 ? reason.substring(0, 500) : reason);
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "UPDATE zhilian_data SET delivery_status = ?, match_reason = ?, update_time = ? "
                             + "WHERE job_id = ? AND (delivery_status IS NULL OR delivery_status <> '已投递' OR ? = '已投递')")) {
            ps.setString(1, status);
            ps.setString(2, r);
            ps.setString(3, LocalDateTime.now().withNano(0).toString());
            ps.setString(4, jobId);
            ps.setString(5, status);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("更新智联岗位判定结果失败：{}", e.getMessage());
        }
    }

    /**
     * 按卡片信息判断岗位是否近期已处理过，口径与 {@link #isRecentlyProcessed(String, LocalDateTime)} 相同。
     *
     * @param title    String，可空，岗位名称；为空时返回 false
     * @param company  String，可空，公司名称；为空时返回 false
     * @param location String，可空，工作地点；为空时返回 false
     * @param cutoff   LocalDateTime，必填，过滤判定的有效起点
     * @return boolean，true 表示近期已处理，应跳过
     */
    public boolean isRecentlyProcessedByCard(String title, String company, String location, LocalDateTime cutoff) {
        if (title == null || title.isBlank() || company == null || company.isBlank()
                || location == null || location.isBlank()) return false;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT delivery_status, update_time FROM zhilian_data "
                             + "WHERE job_title = ? AND company_name = ? AND location = ?")) {
            ps.setString(1, title);
            ps.setString(2, company);
            ps.setString(3, location);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString(1);
                    if ("已投递".equals(status)) return true;
                    if ("已过滤".equals(status)) {
                        LocalDateTime t = parseTime(rs.getString(2));
                        if (t != null && !t.isBefore(cutoff)) return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("按卡片查询智联岗位处理记录失败：{}", e.getMessage());
        }
        return false;
    }

    /**
     * 判断岗位是否近期已处理过：已投递过，或在截止时间之后已被判定过滤。处理过的岗位本次直接跳过。
     *
     * @param jobId  String，可空，智联岗位编号；为空时返回 false
     * @param cutoff LocalDateTime，必填，过滤判定的有效起点
     * @return boolean，true 表示近期已处理，应跳过
     */
    public boolean isRecentlyProcessed(String jobId, LocalDateTime cutoff) {
        if (jobId == null || jobId.isBlank()) return false;
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT delivery_status, update_time FROM zhilian_data WHERE job_id = ?")) {
            ps.setString(1, jobId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = rs.getString(1);
                    if ("已投递".equals(status)) return true;
                    if ("已过滤".equals(status)) {
                        LocalDateTime t = parseTime(rs.getString(2));
                        if (t != null && !t.isBefore(cutoff)) return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("查询智联岗位处理记录失败：{}", e.getMessage());
        }
        return false;
    }

    /**
     * 记录一次真实投递并打招呼，用于每日上限统计。
     *
     * @param jobId       String，可空，岗位编号
     * @param jobTitle    String，可空，岗位名称
     * @param companyName String，可空，公司名称
     */
    public void logGreeting(String jobId, String jobTitle, String companyName) {
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO zhilian_greet_log (greet_date, job_id, job_title, company_name, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, java.time.LocalDate.now().toString());
            ps.setString(2, jobId);
            ps.setString(3, jobTitle);
            ps.setString(4, companyName);
            ps.setString(5, LocalDateTime.now().withNano(0).toString());
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("记录智联打招呼流水失败：{}", e.getMessage());
        }
    }

    /**
     * 统计今日已真实投递并打招呼的次数。
     * 入参：无。
     *
     * @return int，今日次数；查询失败时返回 Integer.MAX_VALUE（按已达上限处理，宁可不发）
     */
    public int countTodayGreetings() {
        try (Connection conn = dataSource.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM zhilian_greet_log WHERE greet_date = ?")) {
            ps.setString(1, java.time.LocalDate.now().toString());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            log.warn("统计智联今日打招呼次数失败，按已达上限处理：{}", e.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 解析数据库中的时间文本。
     *
     * @param text String，可空，形如 2026-11-02T13:00:00 或 2026-11-02 13:00:00.123
     * @return LocalDateTime，解析结果；为空或无法解析时返回 null
     */
    private static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        try {
            if (t.chars().allMatch(Character::isDigit)) {
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(Long.parseLong(t)), java.time.ZoneId.systemDefault());
            }
            return LocalDateTime.parse(t.replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }

    public boolean existsByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return false;
        QueryWrapper<ZhilianJobDataEntity> w = new QueryWrapper<>();
        w.eq("job_id", jobId).last("LIMIT 1");
        Long c = zhilianJobDataMapper.selectCount(w);
        return c != null && c > 0;
    }

    public boolean existsByTitleAndCompany(String jobTitle, String companyName) {
        if (jobTitle == null || companyName == null) return false;
        QueryWrapper<ZhilianJobDataEntity> w = new QueryWrapper<>();
        w.eq("job_title", jobTitle).eq("company_name", companyName).last("LIMIT 1");
        Long c = zhilianJobDataMapper.selectCount(w);
        return c != null && c > 0;
    }

    public void insertJob(ZhilianJobDataEntity entity) {
        if (entity == null) return;
        LocalDateTime now = LocalDateTime.now();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        if (entity.getDeliveryStatus() == null) entity.setDeliveryStatus("未投递");
        zhilianJobDataMapper.insert(entity);
    }

    public void markDeliveredByJobId(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) return;
        ZhilianJobDataEntity upd = new ZhilianJobDataEntity();
        upd.setDeliveryStatus("已投递");
        upd.setUpdateTime(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("job_id", jobId);
        zhilianJobDataMapper.update(upd, uw);
    }

    /**
     * 按 jobId 更新投递状态。
     *
     * @param jobId  String，必填，智联岗位 ID；为空时不更新
     * @param status String，必填，投递状态：已投递 / 已过滤 / 投递失败
     */
    public void markStatusByJobId(String jobId, String status) {
        if (jobId == null || jobId.trim().isEmpty() || status == null) return;
        ZhilianJobDataEntity upd = new ZhilianJobDataEntity();
        upd.setDeliveryStatus(status);
        upd.setUpdateTime(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("job_id", jobId);
        zhilianJobDataMapper.update(upd, uw);
    }

    public void markDeliveredByTitleAndCompany(String jobTitle, String companyName) {
        if (jobTitle == null || companyName == null) return;
        ZhilianJobDataEntity upd = new ZhilianJobDataEntity();
        upd.setDeliveryStatus("已投递");
        upd.setUpdateTime(LocalDateTime.now());
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ZhilianJobDataEntity> uw =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<>();
        uw.eq("job_title", jobTitle).eq("company_name", companyName);
        zhilianJobDataMapper.update(upd, uw);
    }

    // ==================== 投递分析（Dashboard）与列表 ====================

    /** 薪资解析结果 */
    public static class SalaryInfo {
        public Integer minK;      // 最小K（单位：K/月）
        public Integer maxK;      // 最大K（单位：K/月）
        public Integer months;    // 月数（默认12）
        public Double medianK;    // 中位数K
        public Long annualTotal;  // 年包（单位：元）
    }

    /** 解析薪资字符串，支持示例：20-40K、35-65K·16薪、30K·15薪、面议（返回null） */
    public static SalaryInfo parseSalary(String salary) {
        if (salary == null) return null;
        String s = salary.trim();
        if (s.isEmpty()) return null;
        if (s.contains("面议")) return null;
        s = s.replace(" ", "");

        Integer months = 12;
        java.util.regex.Matcher mMonths = java.util.regex.Pattern.compile("[·\\.\\-]?([0-9]+)薪").matcher(s);
        if (mMonths.find()) {
            try { months = Integer.parseInt(mMonths.group(1)); } catch (Exception ignore) {}
            s = s.substring(0, mMonths.start());
        }

        Integer minK = null, maxK = null;
        java.util.regex.Matcher mRange = java.util.regex.Pattern.compile("^(\\d+)-(\\d+)[Kk]$").matcher(s);
        java.util.regex.Matcher mSingle = java.util.regex.Pattern.compile("^(\\d+)[Kk]$").matcher(s);
        if (mRange.find()) {
            try { minK = Integer.parseInt(mRange.group(1)); maxK = Integer.parseInt(mRange.group(2)); } catch (Exception ignore) {}
        } else if (mSingle.find()) {
            try { minK = Integer.parseInt(mSingle.group(1)); maxK = minK; } catch (Exception ignore) {}
        } else {
            String cleaned = s.replaceAll("[^0-9Kk\\-]", "");
            mRange = java.util.regex.Pattern.compile("^(\\d+)-(\\d+)[Kk]$").matcher(cleaned);
            mSingle = java.util.regex.Pattern.compile("^(\\d+)[Kk]$").matcher(cleaned);
            if (mRange.find()) {
                try { minK = Integer.parseInt(mRange.group(1)); maxK = Integer.parseInt(mRange.group(2)); } catch (Exception ignore) {}
            } else if (mSingle.find()) {
                try { minK = Integer.parseInt(mSingle.group(1)); maxK = minK; } catch (Exception ignore) {}
            }
        }

        if (minK == null || maxK == null) return null;

        SalaryInfo info = new SalaryInfo();
        info.minK = minK;
        info.maxK = maxK;
        info.months = months != null ? months : 12;
        info.medianK = (minK + maxK) / 2.0;
        info.annualTotal = Math.round(info.medianK * 1000 * info.months);
        return info;
    }

    /** KPI 指标 */
    public static class Kpi {
        public long total;
        public long delivered;
        public long pending;
        public long filtered; // 智联暂不区分，置0或来源于 delivery_status
        public long failed;   // 智暂不区分，置0或来源于 delivery_status
        public Double avgMonthlyK; // 平均中位数K
    }

    /** 通用 name-value 项 */
    public static class NameValue { public String name; public long value; public NameValue(){} public NameValue(String n,long v){name=n;value=v;} }
    /** 薪资桶 */
    public static class BucketValue { public String bucket; public long value; public BucketValue(){} public BucketValue(String b,long v){bucket=b;value=v;} }

    /** 图表集合 */
    public static class Charts {
        public List<NameValue> byStatus;
        public List<NameValue> byCity;
        public List<NameValue> byCompany;
        public List<NameValue> byExperience;
        public List<NameValue> byDegree;
        public List<BucketValue> salaryBuckets;
        public List<NameValue> dailyTrend; // date 作为 name
    }

    /** 统计响应 */
    public static class StatsResponse { public Kpi kpi; public Charts charts; }

    /** 获取智联投递统计（带筛选） */
    public StatsResponse getZhilianStats(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword
    ) {
        QueryWrapper<ZhilianJobDataEntity> wrapper = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet()));
        }
        if (location != null && !location.trim().isEmpty()) wrapper.eq("location", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("experience", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("degree", degree.trim());
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("company_name", kw)
                    .or().like("job_title", kw));
        }

        wrapper.orderByDesc("create_time");
        List<ZhilianJobDataEntity> all = zhilianJobDataMapper.selectList(wrapper);

        List<ZhilianJobDataEntity> filtered = new ArrayList<>();
        for (ZhilianJobDataEntity e : all) {
            if (minK == null && maxK == null) {
                filtered.add(e);
            } else {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok = ok && (info.medianK >= minK);
                if (maxK != null) ok = ok && (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        Kpi kpi = new Kpi();
        kpi.total = filtered.size();
        kpi.delivered = filtered.stream().filter(e -> "已投递".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.pending = filtered.stream().filter(e -> "未投递".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.filtered = filtered.stream().filter(e -> "已过滤".equals(nullSafe(e.getDeliveryStatus()))).count();
        kpi.failed = filtered.stream().filter(e -> "投递失败".equals(nullSafe(e.getDeliveryStatus()))).count();
        {
            List<Double> medians = new ArrayList<>();
            for (ZhilianJobDataEntity e : filtered) {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                medians.add(info.medianK);
            }
            kpi.avgMonthlyK = medians.isEmpty() ? null : medians.stream().mapToDouble(d -> d).average().orElse(0.0);
        }

        Charts charts = new Charts();
        charts.byStatus = new ArrayList<>();
        charts.byCity = new ArrayList<>();
        charts.byCompany = new ArrayList<>();
        charts.byExperience = new ArrayList<>();
        charts.byDegree = new ArrayList<>();
        charts.salaryBuckets = new ArrayList<>();
        charts.dailyTrend = new ArrayList<>();

        Map<String, Long> byStatus = filtered.stream()
                .collect(Collectors.groupingBy(e -> nullSafe(e.getDeliveryStatus()), Collectors.counting()));
        byStatus.forEach((k, v) -> charts.byStatus.add(new NameValue(k, v)));

        Map<String, Long> byCity = filtered.stream()
                .filter(e -> e.getLocation() != null && !e.getLocation().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getLocation()), Collectors.counting()));
        byCity.forEach((k, v) -> charts.byCity.add(new NameValue(k, v)));

        Map<String, Long> byCompany = filtered.stream()
                .filter(e -> e.getCompanyName() != null && !e.getCompanyName().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getCompanyName()), Collectors.counting()));
        byCompany.forEach((k, v) -> charts.byCompany.add(new NameValue(k, v)));

        Map<String, Long> byExp = filtered.stream()
                .filter(e -> e.getExperience() != null && !e.getExperience().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getExperience()), Collectors.counting()));
        byExp.forEach((k, v) -> charts.byExperience.add(new NameValue(k, v)));

        Map<String, Long> byDegree = filtered.stream()
                .filter(e -> e.getDegree() != null && !e.getDegree().trim().isEmpty())
                .collect(Collectors.groupingBy(e -> nullSafe(e.getDegree()), Collectors.counting()));
        byDegree.forEach((k, v) -> charts.byDegree.add(new NameValue(k, v)));

        // 每日趋势（按日期聚合 create_time）
        Map<String, Long> byDay = filtered.stream()
                .filter(e -> e.getCreateTime() != null)
                .collect(Collectors.groupingBy(e -> e.getCreateTime().toLocalDate().toString(), Collectors.counting()));
        byDay.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(en -> charts.dailyTrend.add(new NameValue(en.getKey(), en.getValue())));

        // 薪资桶
        long b0_10=0,b10_15=0,b15_20=0,b20_top=0,b_ge_top=0;
        double maxMedian = 0.0;
        List<Double> medians = new ArrayList<>();
        for (ZhilianJobDataEntity e : filtered) {
            SalaryInfo info = parseSalary(e.getSalary());
            if (info == null || info.medianK == null) continue;
            double m = info.medianK;
            medians.add(m);
            if (m > maxMedian) maxMedian = m;
        }
        int topEdge = (int) Math.ceil(maxMedian / 5.0) * 5;
        if (topEdge <= 20) topEdge = 25;
        for (double m : medians) {
            if (m < 10) b0_10++;
            else if (m < 15) b10_15++;
            else if (m < 20) b15_20++;
            else if (m < topEdge) b20_top++;
            else b_ge_top++;
        }
        charts.salaryBuckets.add(new BucketValue("0-10K", b0_10));
        charts.salaryBuckets.add(new BucketValue("10-15K", b10_15));
        charts.salaryBuckets.add(new BucketValue("15-20K", b15_20));
        charts.salaryBuckets.add(new BucketValue("20-" + topEdge + "K", b20_top));
        charts.salaryBuckets.add(new BucketValue(">=" + topEdge + "K", b_ge_top));

        StatsResponse resp = new StatsResponse();
        resp.kpi = kpi;
        resp.charts = charts;
        return resp;
    }

    /** 列表查询（分页 + 筛选 + 关键词 + 薪资区间基于中位数K） */
    public PagedResult listZhilianJobs(
            List<String> statuses,
            String location,
            String experience,
            String degree,
            Double minK,
            Double maxK,
            String keyword,
            int page,
            int size
    ) {
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;

        QueryWrapper<ZhilianJobDataEntity> wrapper = new QueryWrapper<>();
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in("delivery_status", statuses.stream().filter(Objects::nonNull).map(String::trim).collect(Collectors.toSet()));
        }
        if (location != null && !location.trim().isEmpty()) wrapper.eq("location", location.trim());
        if (experience != null && !experience.trim().isEmpty()) wrapper.eq("experience", experience.trim());
        if (degree != null && !degree.trim().isEmpty()) wrapper.eq("degree", degree.trim());

        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like("company_name", kw)
                    .or().like("job_title", kw));
        }

        wrapper.orderByDesc("create_time");
        List<ZhilianJobDataEntity> all = zhilianJobDataMapper.selectList(wrapper);

        List<ZhilianJobDataEntity> filtered = new ArrayList<>();
        for (ZhilianJobDataEntity e : all) {
            if (minK == null && maxK == null) {
                filtered.add(e);
            } else {
                SalaryInfo info = parseSalary(e.getSalary());
                if (info == null || info.medianK == null) continue;
                boolean ok = true;
                if (minK != null) ok = ok && (info.medianK >= minK);
                if (maxK != null) ok = ok && (info.medianK <= maxK);
                if (ok) filtered.add(e);
            }
        }

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);

        PagedResult pr = new PagedResult();
        pr.items = filtered.subList(from, to);
        pr.total = total;
        pr.page = page;
        pr.size = size;
        return pr;
    }

    public static class PagedResult {
        public List<ZhilianJobDataEntity> items;
        public long total;
        public int page;
        public int size;
    }

    private static String nullSafe(String s) { return s == null ? "" : s.trim(); }
}