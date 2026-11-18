package com.simplecard.config;

import com.simplecard.service.PublicRequestBounds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 分页参数统一闸门 —— 一处拦截，覆盖所有接口。
 *
 * <p><b>为什么不在每个 controller 里写：</b>项目里 9 个 controller 各自接 {@code page}
 * 与 {@code page_size}，只有 1 个做过校验。逐个补的结局是可预见的：新增接口时忘掉，
 * 或者某个分支漏掉。放在拦截器里，新接口天然被覆盖。
 *
 * <p><b>不设上限会怎样：</b>{@code page_size=100000} 会被原样塞进
 * {@code PageRequest.of(page, size)}，一次请求就把整张表读进内存——公开接口上这是
 * 低成本的打穿手段，管理端则是误操作就能拖垮库。
 *
 * <p><b>为什么 {@code page=0} 也要拦：</b>部分实现用 {@code PageRequest.of(page - 1, size)}，
 * 0 会变成 -1 抛 {@code IllegalArgumentException}，最终以 500 返回。这类入参错误应该是 400，
 * 不该让调用方看到"系统错误"。
 *
 * <p>上限与 {@link PublicRequestBounds#pagination} 保持一致（page ≤ 10000、size ≤ 100），
 * 避免出现"同一个参数两套规矩"。
 */
@Component
public class PageBoundsInterceptor implements HandlerInterceptor {

    /** 页码上限：再大也只是空页，没有业务意义 */
    static final int MAX_PAGE = 10_000;
    /** 单页上限：与 PublicRequestBounds 一致，前端最大只用到 100 */
    static final int MAX_PAGE_SIZE = 100;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        check(request.getParameter("page"), 1, MAX_PAGE, "page");

        // 兼容 page_size（项目主流写法）与 pageSize（个别接口）
        String size = request.getParameter("page_size");
        if (size == null) {
            size = request.getParameter("pageSize");
        }
        check(size, 1, MAX_PAGE_SIZE, "page_size");
        return true;
    }

    private void check(String raw, int min, int max, String name) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // 非数字也归入参数错误：交给全局处理器转 400，而不是让它冒到 500
            throw PublicRequestBounds.bad();
        }
        if (value < min || value > max) {
            throw PublicRequestBounds.bad();
        }
    }
}
