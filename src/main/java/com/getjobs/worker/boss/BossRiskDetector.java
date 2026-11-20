package com.getjobs.worker.boss;

import org.json.JSONObject;

/**
 * Boss 平台风控识别规则：根据页面地址、页面文字、接口返回判断是否触发了安全验证或访问受限。
 * 只做判断，不操作页面。
 */
public final class BossRiskDetector {

    /** 风控类型：账号或 IP 被限制访问 */
    public static final String TYPE_RESTRICTED = "访问受限";
    /** 风控类型：要求完成滑块等安全验证 */
    public static final String TYPE_VERIFY = "安全验证";
    /** 风控类型：岗位列表或详情接口返回异常 */
    public static final String TYPE_API = "接口异常";

    /** 页面正文超过该长度视为正常业务页面，不按正文判定（避免岗位描述里的字眼误判） */
    static final int SHORT_PAGE_TEXT_LIMIT = 600;

    private BossRiskDetector() {
    }

    /** 访问受限页路径 */
    private static final java.util.List<String> RESTRICTED_PATHS = java.util.List.of("/web/passport/zp/403.html");
    /** 安全验证页路径（只认页面本身，不认同目录下的脚本等资源） */
    private static final java.util.List<String> VERIFY_PATHS = java.util.List.of(
            "/web/passport/zp/verify.html", "/web/passport/zp/security.html",
            "/web/user/safe/verify-slider", "/web/common/security-check.html");

    /**
     * 按页面地址判断风控类型。只比较路径（去掉查询参数与锚点），且必须与已知风控页路径完全一致，
     * 脚本、样式、图片等资源地址一律不算。
     *
     * @param url String，可空，页面地址
     * @return String，风控类型（{@link #TYPE_RESTRICTED} / {@link #TYPE_VERIFY}）；不是风控页时为 null
     */
    public static String classifyUrl(String url) {
        String path = pathOf(url);
        if (path == null) return null;
        if (RESTRICTED_PATHS.contains(path)) return TYPE_RESTRICTED;
        if (VERIFY_PATHS.contains(path)) return TYPE_VERIFY;
        return null;
    }

    /**
     * 取地址的路径部分，去掉协议、域名、查询参数、锚点与末尾的斜杠。
     *
     * @param url String，可空，地址
     * @return String，路径，如 /web/passport/zp/403.html；地址为空或无法解析时为 null
     */
    static String pathOf(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.trim();
        int cut = u.length();
        for (char c : new char[]{'?', '#'}) {
            int i = u.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        u = u.substring(0, cut);
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            int slash = u.indexOf('/', scheme + 3);
            u = slash < 0 ? "/" : u.substring(slash);
        }
        while (u.length() > 1 && u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    /**
     * 按页面标题与正文判断风控类型。正文较长（正常业务页面）时只看标题。
     *
     * @param title      String，可空，页面标题
     * @param bodyText   String，可空，页面正文（可只传前若干字）
     * @param bodyLength int，必填，页面正文总字数
     * @return String，风控类型；未命中时为 null
     */
    public static String classifyPageText(String title, String bodyText, int bodyLength) {
        String t = title == null ? "" : title;
        if (t.contains("访问受限") || t.contains("异常行为") || t.contains("限制访问")) return TYPE_RESTRICTED;
        if (t.contains("安全验证") || t.contains("安全检测")) return TYPE_VERIFY;
        if (bodyText == null || bodyLength > SHORT_PAGE_TEXT_LIMIT) return null;
        if (bodyText.contains("访问受限") || bodyText.contains("账户存在异常行为")
                || bodyText.contains("限制访问") || bodyText.contains("暂时无法访问")) {
            return TYPE_RESTRICTED;
        }
        if (bodyText.contains("安全验证") || bodyText.contains("请完成验证") || bodyText.contains("拖动滑块")
                || bodyText.contains("点击按钮进行验证") || bodyText.contains("环境异常")) {
            return TYPE_VERIFY;
        }
        return null;
    }

    /**
     * 判断岗位列表 / 岗位详情接口的返回是否异常。HTTP 状态非 2xx、返回不是 JSON、
     * 或 JSON 里的 code 不为 0，都视为异常。
     *
     * @param status int，必填，HTTP 状态码
     * @param body   String，可空，响应正文
     * @return String，异常说明；正常时为 null
     */
    public static String classifyApiResponse(int status, String body) {
        if (status < 200 || status >= 300) return "HTTP 状态 " + status;
        if (body == null || body.isBlank()) return null;
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) return "返回内容不是 JSON";
        try {
            JSONObject root = new JSONObject(trimmed);
            if (!root.has("code")) return null;
            int code = root.optInt("code", 0);
            if (code == 0) return null;
            String message = root.optString("message", "");
            return "code=" + code + (message.isEmpty() ? "" : "，" + message);
        } catch (Exception e) {
            return "返回内容无法解析";
        }
    }

    /**
     * 是否是需要检查返回码的 Boss 岗位接口（列表搜索、岗位详情）。
     *
     * @param url String，可空，请求地址
     * @return boolean，true 表示需要检查
     */
    public static boolean isWatchedApi(String url) {
        return url != null && (url.contains("/wapi/zpgeek/search/joblist.json")
                || url.contains("/wapi/zpgeek/job/detail.json"));
    }

    /**
     * 是否是岗位列表搜索接口。
     *
     * @param url String，可空，请求地址
     * @return boolean，true 表示是列表接口
     */
    public static boolean isJobListApi(String url) {
        return url != null && url.contains("/wapi/zpgeek/search/joblist.json");
    }
}
