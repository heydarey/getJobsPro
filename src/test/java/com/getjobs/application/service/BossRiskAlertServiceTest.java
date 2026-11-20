package com.getjobs.application.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 风控提醒入库、查询未处理、确认处理。数据库文件放在 build 目录下，测试后删除。 */
class BossRiskAlertServiceTest {

    private File dbFile;
    private BossRiskAlertService service;

    @BeforeEach
    void setUp() {
        File dir = new File("build/test-db");
        dir.mkdirs();
        dbFile = new File(dir, "risk-alert-" + System.nanoTime() + ".db");
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        service = new BossRiskAlertService(ds);
        service.ensureTable();
    }

    @AfterEach
    void tearDown() {
        dbFile.delete();
    }

    @Test
    void noAlertByDefault() {
        assertNull(service.getActiveAlert());
        assertEquals(0, service.acknowledge());
    }

    @Test
    void latestUnhandledAlertBlocksUntilAcknowledged() {
        service.record("安全验证", "https://www.zhipin.com/web/passport/zp/verify.html", "前端开发", "前端工程师（远程）",
                "页面跳转到安全验证页");
        service.record("访问受限", "https://www.zhipin.com/web/passport/zp/403.html", "前端开发", null,
                "页面停在访问受限页");
        Map<String, Object> alert = service.getActiveAlert();
        assertNotNull(alert);
        assertEquals("访问受限", alert.get("alertType"));
        assertEquals("前端开发", alert.get("keyword"));
        assertEquals(2, alert.get("unhandledCount"));
        assertNotNull(alert.get("createdAt"));

        assertEquals(2, service.acknowledge());
        assertNull(service.getActiveAlert());

        service.record("接口异常", "https://www.zhipin.com/wapi/zpgeek/job/detail.json", "Vue", "前端开发",
                "岗位详情接口返回异常：code=37");
        assertEquals("接口异常", service.getActiveAlert().get("alertType"));
    }
}
