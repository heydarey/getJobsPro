package com.getjobs.worker;

import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 城市代码换算：Boss 与智联在城市为空或选「不限」（代码 0）时都按全国搜索。
 */
class CityCodeResolveTest {

    /**
     * 调用 Boss 的城市换算。
     *
     * @param codes List&lt;String&gt;，可空，配置中的城市代码
     * @return List&lt;String&gt;，换算后的城市代码
     * @throws Exception 反射调用失败时抛出
     */
    @SuppressWarnings("unchecked")
    private static List<String> boss(List<String> codes) throws Exception {
        Boss boss = new Boss(null, null, null);
        BossConfig config = new BossConfig();
        config.setCityCode(codes);
        boss.setConfig(config);
        Method m = Boss.class.getDeclaredMethod("resolveCityCodes");
        m.setAccessible(true);
        return (List<String>) m.invoke(boss);
    }

    /**
     * 调用智联的城市换算。
     *
     * @param code String，可空，配置中的城市代码
     * @return String，换算后的城市代码
     * @throws Exception 反射调用失败时抛出
     */
    private static String zhilian(String code) throws Exception {
        ZhiLian z = new ZhiLian(null, null, null);
        ZhilianConfig config = new ZhilianConfig();
        config.setCityCode(code);
        z.setConfig(config);
        Method m = ZhiLian.class.getDeclaredMethod("resolveCityCode");
        m.setAccessible(true);
        return (String) m.invoke(z);
    }

    @Test
    void bossUnlimitedOrEmptyMeansNationwide() throws Exception {
        assertEquals(List.of("100010000"), boss(new ArrayList<>(List.of("0"))));
        assertEquals(List.of("100010000"), boss(new ArrayList<>()));
        assertEquals(List.of("100010000"), boss(null));
        assertEquals(List.of("100010000"), boss(new ArrayList<>(Arrays.asList("0", "100010000"))));
        assertEquals(List.of("101020100"), boss(new ArrayList<>(List.of("101020100"))));
    }

    @Test
    void zhilianUnlimitedOrEmptyMeansNationwide() throws Exception {
        assertEquals("489", zhilian("0"));
        assertEquals("489", zhilian(""));
        assertEquals("489", zhilian(null));
        assertEquals("538", zhilian("538"));
    }
}
