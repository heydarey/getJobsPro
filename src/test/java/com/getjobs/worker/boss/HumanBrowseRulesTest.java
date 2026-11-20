package com.getjobs.worker.boss;

import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 真人化浏览规则：鼠标轨迹、落点、滚轮分格、阅读停留、卡片黑名单。 */
class HumanBrowseRulesTest {

    @RepeatedTest(20)
    void mousePathEndsExactlyOnTargetWithBoundedSteps() {
        Random random = new Random();
        double x0 = random.nextInt(1200), y0 = random.nextInt(800);
        double x1 = random.nextInt(1200), y1 = random.nextInt(800);
        List<double[]> path = HumanMouse.path(x0, y0, x1, y1, random);
        double[] last = path.get(path.size() - 1);
        assertEquals(x1, last[0], 1e-9);
        assertEquals(y1, last[1], 1e-9);
        double dist = Math.hypot(x1 - x0, y1 - y0);
        if (dist >= 3) {
            assertTrue(path.size() >= 6 && path.size() <= 18, "步数 " + path.size());
            // 轨迹不会偏离连线太远（控制点最多偏离距离的 25%）
            for (double[] p : path) {
                assertTrue(Math.hypot(p[0] - x0, p[1] - y0) <= dist * 1.5 + 1);
            }
        }
    }

    @Test
    void mousePathIsCurvedNotStraight() {
        // 同一组起终点多次生成，至少有一次中间点明显偏离直线
        boolean curved = false;
        Random random = new Random(7);
        for (int i = 0; i < 10 && !curved; i++) {
            List<double[]> path = HumanMouse.path(0, 0, 600, 0, random);
            for (double[] p : path) {
                if (Math.abs(p[1]) > 10) curved = true;
            }
        }
        assertTrue(curved);
    }

    @RepeatedTest(50)
    void clickPointStaysInsideCardAwayFromEdges() {
        BoundingBox box = new BoundingBox();
        box.x = 100;
        box.y = 200;
        box.width = 400;
        box.height = 120;
        double[] p = HumanMouse.randomPointIn(box, new Random());
        assertTrue(p[0] >= 100 + 400 * 0.15 - 1e-9 && p[0] <= 100 + 400 * 0.85 + 1e-9);
        assertTrue(p[1] >= 200 + 120 * 0.25 - 1e-9 && p[1] <= 200 + 120 * 0.75 + 1e-9);
    }

    @Test
    void wheelTicksSumToDistanceWithRealisticTickSize() {
        Random random = new Random(3);
        for (int distance : new int[]{560, -430, 95, 1000}) {
            List<Integer> ticks = HumanMouse.wheelTicks(distance, random);
            assertEquals(distance, ticks.stream().mapToInt(Integer::intValue).sum());
            for (int i = 0; i < ticks.size() - 1; i++) {
                int t = Math.abs(ticks.get(i));
                assertTrue(t >= HumanMouse.WHEEL_TICK_MIN && t <= HumanMouse.WHEEL_TICK_MAX);
                assertEquals(Integer.signum(distance), Integer.signum(ticks.get(i)));
            }
        }
        assertTrue(HumanMouse.wheelTicks(0, random).isEmpty());
    }

    @Test
    void readingTimeGrowsWithDescriptionLengthWithinBounds() {
        Random random = new Random(11);
        for (int i = 0; i < 200; i++) {
            long shortRead = Boss.readingMillis(50, random);
            long longRead = Boss.readingMillis(5000, random);
            assertTrue(shortRead >= 3_000 && shortRead <= 5_300, "短描述 " + shortRead);
            assertTrue(longRead >= 15_500 && longRead <= 17_500, "长描述 " + longRead);
        }
        long mid = Boss.readingMillis(230, new Random(1));
        assertTrue(mid > 8_000 && mid < 17_500, "中等描述 " + mid);
    }

    @Test
    void cardBlacklistMatchesJobAndCompanyOnly() {
        Set<String> jobs = Set.of("外包", "");
        Set<String> companies = Set.of("某某人力");
        assertEquals("职位黑名单命中：外包", Boss.cardBlacklistHit("前端开发（外包）", "甲公司", jobs, companies));
        assertEquals("公司黑名单命中：某某人力", Boss.cardBlacklistHit("前端开发", "某某人力资源", jobs, companies));
        assertNull(Boss.cardBlacklistHit("前端开发", "甲公司", jobs, companies));
        assertNull(Boss.cardBlacklistHit("", "", jobs, companies));
        assertNull(Boss.cardBlacklistHit("前端开发", "甲公司", null, null));
    }
}
