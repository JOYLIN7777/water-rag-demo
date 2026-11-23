package com.joylin.waterkb.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存用户库：启动时从配置加载账号，明文密码立即转成 PBKDF2 哈希，明文不驻留。
 *
 * <p>作品集定位：账号通过 {@code security.auth.users[*]} 配置（密码走环境变量注入），
 * 启动即哈希。生产环境应替换为数据库用户表 + 注册审批流，接口 {@link #authenticate} 保持不变，
 * 上层登录服务无需改动。</p>
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    /**
     * 账号配置串：多个账号用分号分隔，每个账号形如
     * {@code 用户名|密码|组(逗号分隔)|租户|显示名}。
     * 用单字符串而非 YAML 数组，是为了能被 @Value 正确读取，也便于整体用环境变量覆盖。
     */
    private final String configuredUsers;
    private final PasswordHasher hasher;
    private final Map<String, UserAccount> users = new LinkedHashMap<>();

    public UserStore(PasswordHasher hasher,
                     @Value("${security.auth.users:}") String configuredUsers) {
        this.hasher = hasher;
        this.configuredUsers = configuredUsers == null ? "" : configuredUsers;
    }

    @PostConstruct
    public void init() {
        for (String line : configuredUsers.split(";")) {
            if (line == null || line.isBlank()) continue;
            // 格式：username|password|groups(逗号分隔)|tenant|displayName
            String[] p = line.split("\\|", 5);
            if (p.length < 3) {
                log.warn("忽略格式错误的用户配置：{}", line);
                continue;
            }
            String username = p[0].trim();
            String rawPwd = p[1];
            List<String> groups = java.util.Arrays.stream(p[2].split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            String tenant = p.length > 3 && !p[3].isBlank() ? p[3].trim() : "default";
            String display = p.length > 4 && !p[4].isBlank() ? p[4].trim() : username;
            users.put(username, new UserAccount(username, hasher.hash(rawPwd), groups, tenant, display));
            log.info("已加载用户：{}（组 {}，租户 {}）", username, groups, tenant);
        }
        if (users.isEmpty()) {
            log.warn("未配置任何登录账号，登录接口将不可用。请配置 security.auth.users");
        }
    }

    /** 校验用户名密码，成功返回账号，失败返回 null。用户名比较忽略大小写。 */
    public UserAccount authenticate(String username, String password) {
        if (username == null || password == null) return null;
        UserAccount u = users.get(username.trim().toLowerCase());
        if (u == null || !u.enabled()) return null;
        return hasher.matches(password, u.passwordHash()) ? u : null;
    }

    public UserAccount find(String username) {
        return username == null ? null : users.get(username.trim().toLowerCase());
    }

    /**
     * 修改密码：校验原密码后替换为新密码哈希。返回是否成功。
     * 账号值对象不可变，这里按 key 重建一个新 UserAccount 覆盖。
     */
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        UserAccount u = authenticate(username, oldPassword);
        if (u == null || newPassword == null || newPassword.length() < 8) return false;
        users.put(u.username(), new UserAccount(
                u.username(), hasher.hash(newPassword), u.groups(), u.tenant(), u.displayName(), u.enabled()));
        log.info("用户 {} 密码已更新", u.username());
        return true;
    }

    public List<UserAccount> list() {
        return new ArrayList<>(users.values());
    }

    /** 不可变账号值对象：绝不暴露密码哈希给外部（toString 里也不含） */
    public record UserAccount(String username, String passwordHash, List<String> groups,
                              String tenant, String displayName, boolean enabled) {
        public UserAccount(String username, String passwordHash, List<String> groups, String tenant, String displayName) {
            this(username, passwordHash, List.copyOf(groups), tenant, displayName, true);
        }
        @Override
        public String toString() {
            return "UserAccount{username='" + username + "', tenant='" + tenant + "', groups=" + groups + ", enabled=" + enabled + '}';
        }
    }
}
