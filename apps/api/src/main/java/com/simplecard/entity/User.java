package com.simplecard.entity;

import com.simplecard.constant.UserRole;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    @Column(unique = true, nullable = false)
    private String username;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role = UserRole.USER;

    private int points = 0;

    private int isDeleted = 0;

    /** 连续登录失败次数 */
    private int failedLoginAttempts = 0;

    /** 账户锁定截止时间（null 表示未锁定） */
    private LocalDateTime lockUntil;

    /**
     * 权限撤销分界点 —— 此时间点之前签发的 JWT 一律失效。
     *
     * <p>无状态 JWT 本身无法撤销，登出/改密/封禁在令牌自然过期（24 小时）前都不会生效。
     * 用一个时间戳与 JWT 的 iat 比对即可实现撤销，且跨重启、跨实例都有效。
     *
     * <p>写入时截断到秒：JWT 的 iat 只有秒级精度，若保留毫秒，登出后同一秒内重新登录
     * 拿到的新令牌会因 iat 被截断而误判为"早于分界点"，导致刚登录就被踢出。
     */
    private LocalDateTime tokensInvalidBefore;
}
