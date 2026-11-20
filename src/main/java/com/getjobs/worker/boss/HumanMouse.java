package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟真人的鼠标操作：沿带弧度的轨迹移动、在元素内随机落点点击、用滚轮分段滚动。
 * 每个标签页记录自己的鼠标位置。
 */
final class HumanMouse {

    /** 滚轮每格的像素范围 */
    static final int WHEEL_TICK_MIN = 90, WHEEL_TICK_MAX = 150;

    private final Page page;
    private double x = -1;
    private double y = -1;

    /**
     * @param page Page，必填，鼠标所在的标签页
     */
    HumanMouse(Page page) {
        this.page = page;
    }

    /**
     * 生成从起点到终点的鼠标轨迹：三次贝塞尔曲线，两个控制点在连线两侧随机偏移，
     * 速度先快后慢，终点精确落在目标上。
     *
     * @param x0     double，必填，起点横坐标
     * @param y0     double，必填，起点纵坐标
     * @param x1     double，必填，终点横坐标
     * @param y1     double，必填，终点纵坐标
     * @param random Random，必填，随机数源
     * @return List&lt;double[]&gt;，轨迹点（不含起点，含终点），至少 1 个
     */
    static List<double[]> path(double x0, double y0, double x1, double y1, Random random) {
        double dx = x1 - x0, dy = y1 - y0;
        double dist = Math.hypot(dx, dy);
        List<double[]> points = new ArrayList<>();
        if (dist < 3) {
            points.add(new double[]{x1, y1});
            return points;
        }
        // 法向量，用于把控制点推到连线一侧
        double nx = -dy / dist, ny = dx / dist;
        double bend1 = (random.nextDouble() - 0.5) * 0.5 * dist;
        double bend2 = (random.nextDouble() - 0.5) * 0.3 * dist;
        double c1x = x0 + dx * 0.3 + nx * bend1, c1y = y0 + dy * 0.3 + ny * bend1;
        double c2x = x0 + dx * 0.7 + nx * bend2, c2y = y0 + dy * 0.7 + ny * bend2;
        int steps = (int) Math.max(6, Math.min(18, dist / 30));
        for (int i = 1; i <= steps; i++) {
            double s = (double) i / steps;
            // 缓出：前段快、接近目标时慢
            double t = 1 - Math.pow(1 - s, 2.2);
            double u = 1 - t;
            double px = u * u * u * x0 + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * x1;
            double py = u * u * u * y0 + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * y1;
            points.add(new double[]{px, py});
        }
        points.set(points.size() - 1, new double[]{x1, y1});
        return points;
    }

    /**
     * 在元素范围内取一个随机落点：偏向中部，不贴边。
     *
     * @param box    BoundingBox，必填，元素在视口中的位置
     * @param random Random，必填，随机数源
     * @return double[]，{x, y}
     */
    static double[] randomPointIn(BoundingBox box, Random random) {
        double rx = clamp(0.5 + random.nextGaussian() * 0.17, 0.15, 0.85);
        double ry = clamp(0.5 + random.nextGaussian() * 0.15, 0.25, 0.75);
        return new double[]{box.x + box.width * rx, box.y + box.height * ry};
    }

    /**
     * 把滚动距离拆成若干个滚轮格，每格像素随机。
     *
     * @param distance int，必填，总滚动像素，负数表示向上
     * @param random   Random，必填，随机数源
     * @return List&lt;Integer&gt;，每格的滚动像素（带方向），总和约等于 distance
     */
    static List<Integer> wheelTicks(int distance, Random random) {
        List<Integer> ticks = new ArrayList<>();
        int sign = distance < 0 ? -1 : 1;
        int remaining = Math.abs(distance);
        while (remaining > 0) {
            int tick = WHEEL_TICK_MIN + random.nextInt(WHEEL_TICK_MAX - WHEEL_TICK_MIN + 1);
            tick = Math.min(tick, remaining);
            ticks.add(sign * tick);
            remaining -= tick;
            if (ticks.size() >= 40) break;
        }
        return ticks;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 沿轨迹把鼠标移到指定位置。
     *
     * @param tx double，必填，目标横坐标（视口坐标）
     * @param ty double，必填，目标纵坐标（视口坐标）
     */
    void moveTo(double tx, double ty) {
        Random random = ThreadLocalRandom.current();
        if (x < 0 || y < 0) {
            // 首次移动：从视口中部附近的随机位置出发
            double[] size = viewport();
            x = size[0] * (0.3 + random.nextDouble() * 0.4);
            y = size[1] * (0.3 + random.nextDouble() * 0.4);
            page.mouse().move(x, y);
        }
        for (double[] p : path(x, y, tx, ty, random)) {
            page.mouse().move(p[0], p[1]);
        }
        x = tx;
        y = ty;
    }

    /**
     * 当前鼠标位置。
     *
     * @return double[]，{x, y}；尚未移动过时为 {-1, -1}
     */
    double[] position() {
        return new double[]{x, y};
    }

    /**
     * 视口宽高。
     *
     * @return double[]，{宽, 高}；取不到时按 1280×800
     */
    double[] viewport() {
        try {
            Object size = page.evaluate("() => [window.innerWidth, window.innerHeight]");
            if (size instanceof List<?> list && list.size() == 2) {
                return new double[]{((Number) list.get(0)).doubleValue(), ((Number) list.get(1)).doubleValue()};
            }
        } catch (Exception ignore) {
        }
        return new double[]{1280, 800};
    }

    /**
     * 在元素内取一个不落在链接、按钮上的随机点。最多尝试 6 次，都不合适时用元素中心。
     *
     * @param target Locator，必填，目标元素
     * @param box    BoundingBox，必填，目标元素位置
     * @return double[]，{x, y}
     */
    private double[] pickPoint(Locator target, BoundingBox box) {
        Random random = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            double[] p = randomPointIn(box, random);
            try {
                Object ok = target.evaluate("(el, p) => { const h = document.elementFromPoint(p[0], p[1]);"
                        + " if (!h || !el.contains(h)) return false;"
                        + " const a = h.closest('a,button,input,textarea');"
                        + " return !a || a.contains(el); }", List.of(p[0], p[1]));
                if (Boolean.TRUE.equals(ok)) return p;
            } catch (Exception ignore) {
                break;
            }
        }
        return new double[]{box.x + box.width / 2, box.y + box.height / 2};
    }

    /**
     * 沿轨迹移到元素内随机落点并点击。取不到元素位置时退回普通点击。
     *
     * @param target Locator，必填，目标元素
     * @param pauser Pauser，必填，可被停止打断的停顿
     */
    void click(Locator target, Pauser pauser) {
        BoundingBox box = null;
        try {
            box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(3_000));
        } catch (Exception ignore) {
        }
        if (box == null || box.width < 2 || box.height < 2) {
            target.click(new Locator.ClickOptions().setTimeout(5_000));
            return;
        }
        double[] size = viewport();
        if (box.y < 0 || box.y + box.height > size[1] || box.x < 0 || box.x + box.width > size[0]) {
            // 元素不在可视区域内（调用方没有先滚过去）：先让它进入视口再点
            try {
                target.scrollIntoViewIfNeeded(new Locator.ScrollIntoViewIfNeededOptions().setTimeout(3_000));
                box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(3_000));
            } catch (Exception ignore) {
            }
            if (box == null) {
                target.click(new Locator.ClickOptions().setTimeout(5_000));
                return;
            }
        }
        double[] p = pickPoint(target, box);
        moveTo(p[0], p[1]);
        pauser.pause(80, 260);
        page.mouse().down();
        pauser.pause(40, 110);
        page.mouse().up();
    }

    /**
     * 沿轨迹把鼠标移到元素内某处停留（不点击）。
     *
     * @param target Locator，必填，目标元素
     */
    void hover(Locator target) {
        try {
            BoundingBox box = target.boundingBox(new Locator.BoundingBoxOptions().setTimeout(2_000));
            if (box == null) return;
            double[] p = randomPointIn(box, ThreadLocalRandom.current());
            moveTo(p[0], p[1]);
        } catch (Exception ignore) {
        }
    }

    /**
     * 在当前鼠标位置用滚轮分格滚动，格与格之间短暂停顿。
     *
     * @param distance int，必填，总滚动像素，负数向上
     * @param pauser   Pauser，必填，可被停止打断的停顿
     */
    void wheel(int distance, Pauser pauser) {
        for (int tick : wheelTicks(distance, ThreadLocalRandom.current())) {
            page.mouse().wheel(0, tick);
            pauser.pause(30, 120);
        }
    }

    /** 可被停止打断的停顿 */
    @FunctionalInterface
    interface Pauser {
        /**
         * @param minMillis long，必填，最短毫秒
         * @param maxMillis long，必填，最长毫秒
         */
        void pause(long minMillis, long maxMillis);
    }
}
