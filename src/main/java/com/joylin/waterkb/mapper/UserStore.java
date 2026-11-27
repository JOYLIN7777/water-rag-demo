package com.joylin.waterkb.mapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.joylin.waterkb.security.PasswordHasher;

/**
 * 内存用户库：启动时从配置加载账号，明文密码立即转成 PBKDF2 哈希，明文不驻留。
 *
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
    /**
     * 可为 null：单测与无数据库环境只走内存用户库
     */
    private final UserMapper mapper;
    private final Map<String, UserAccount> users = new LinkedHashMap<>();

    /**

     * 兼容单测：不接数据库，纯内存

     */
    public UserStore(PasswordHasher hasher,
                     @Value("${security.auth.users:}") String configuredUsers) {
        this(hasher, configuredUsers, null);
    }

    /**

     * 生产构造：后台维护的账号写入 app_users，重启后仍生效

     */
    @Autowired
    public UserStore(PasswordHasher hasher,
                     @Value("${security.auth.users:}") String configuredUsers,
                     UserMapper mapper) {
        this.hasher = hasher;
        this.configuredUsers = configuredUsers == null ? "" : configuredUsers;
        this.mapper = mapper;
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
        loadFromDatabase();
    }

    /**

     * 数据库账号覆盖同名的配置账号，保证后台编辑的结果重启后不丢失

     */
    private void loadFromDatabase() {
        if (mapper == null) return;
        try {
            for (Map<String, Object> row : mapper.listAll()) {
                String username = String.valueOf(row.get("username")).trim().toLowerCase();
                List<String> groups = Arrays.stream(String.valueOf(row.get("groups")).split(","))
                        .map(String::trim).filter(x -> !x.isEmpty()).toList();
                users.put(username, new UserAccount(username, String.valueOf(row.get("passwordHash")), groups,
                        String.valueOf(row.get("tenant")), String.valueOf(row.get("displayName")),
                        Boolean.TRUE.equals(row.get("enabled")),
                        nvl(String.valueOf(row.get("gender"))), nvl(String.valueOf(row.get("phone"))),
                        nvl(String.valueOf(row.get("avatar")))));
            }
            log.info("已从 app_users 载入账号，当前账号数 {}", users.size());
        } catch (Exception e) {
            log.warn("账号持久化表不可用，后台修改仅在本次运行生效：{}", e.getMessage());
        }
    }

    private static String nvl(String s) { return s == null ? "" : s.trim(); }

    private void persist(UserAccount u) {
        if (mapper == null) return;
        try {
            mapper.upsert(u.username(), u.passwordHash(), String.join(",", u.groups()), u.tenant(),
                    u.displayName(), u.enabled(), nvl(u.gender()), nvl(u.phone()), nvl(u.avatar()));
        } catch (Exception e) {
            throw new IllegalStateException("账号保存失败：" + e.getMessage(), e);
        }
    }

    /**

     * 后台新增账号；用户名小写唯一，初始密码至少 8 位

     */
    public boolean create(String username, String rawPassword, List<String> groups,
                          String tenant, String displayName) {
        String name = username == null ? "" : username.trim().toLowerCase();
        if (name.isBlank() || users.containsKey(name)) return false;
        if (rawPassword == null || rawPassword.length() < 8) return false;
        UserAccount u = new UserAccount(name, hasher.hash(rawPassword),
                groups == null || groups.isEmpty() ? List.of("viewer") : groups,
                tenant == null || tenant.isBlank() ? "default" : tenant.trim(),
                displayName == null || displayName.isBlank() ? name : displayName.trim(), true);
        users.put(name, u);
        persist(u);
        log.info("后台创建账号 {}", name);
        return true;
    }

    /**

     * 后台编辑：显示名、角色组、租户、启用状态（不改密码）

     */
    public boolean update(String username, String displayName, List<String> groups,
                          String tenant, boolean enabled) {
        String name = username == null ? "" : username.trim().toLowerCase();
        UserAccount u = users.get(name);
        if (u == null) return false;
        UserAccount next = new UserAccount(name, u.passwordHash(),
                groups == null || groups.isEmpty() ? u.groups() : groups,
                tenant == null || tenant.isBlank() ? u.tenant() : tenant.trim(),
                displayName == null || displayName.isBlank() ? u.displayName() : displayName.trim(),
                enabled);
        users.put(name, next);
        persist(next);
        log.info("后台更新账号 {}", name);
        return true;
    }

    /**

     * 启用/停用账号（新增时可创建即停用）

     */
    public boolean setEnabled(String username, boolean enabled) {
        String name = username == null ? "" : username.trim().toLowerCase();
        UserAccount u = users.get(name);
        if (u == null) return false;
        users.put(name, new UserAccount(u.username(), u.passwordHash(), u.groups(), u.tenant(),
                u.displayName(), enabled, u.gender(), u.phone(), u.avatar()));
        persist(users.get(name));
        return true;
    }

    /**

     * 后台删除账号；禁止删除自己由接口层校验

     */
    public boolean delete(String username) {
        String name = username == null ? "" : username.trim().toLowerCase();
        if (!users.containsKey(name)) return false;
        users.remove(name);
        if (mapper != null) {
            try { mapper.delete(name); }
            catch (Exception e) { throw new IllegalStateException("账号删除失败：" + e.getMessage(), e); }
        }
        log.info("后台删除账号 {}", name);
        return true;
    }

    /**

     * 后台重置密码（不校验原密码）

     */
    public boolean resetPassword(String username, String newPassword) {
        String name = username == null ? "" : username.trim().toLowerCase();
        UserAccount u = users.get(name);
        if (u == null || newPassword == null || newPassword.length() < 8) return false;
        UserAccount next = new UserAccount(name, hasher.hash(newPassword), u.groups(),
                u.tenant(), u.displayName(), u.enabled());
        users.put(name, next);
        persist(next);
        log.info("后台重置账号密码 {}", name);
        return true;
    }

    /**

     * 校验用户名/手机号 + 密码，成功返回账号。手机号只按精确匹配，且必须是完整 11 位。

     */
    public UserAccount authenticate(String username, String password) {
        if (username == null || password == null) return null;
        String key = username.trim();
        UserAccount u = users.get(key.toLowerCase());
        if (u == null && key.matches("\\d{11}")) {
            u = users.values().stream().filter(a -> key.equals(a.phone())).findFirst().orElse(null);
        }
        if (u == null || !u.enabled()) return null;
        return hasher.matches(password, u.passwordHash()) ? u : null;
    }

    /**

     * 个人资料：性别、手机号、头像；手机号全局唯一，避免一人多号混用

     */
    public boolean updateProfile(String username, String gender, String phone, String avatar) {
        String name = username == null ? "" : username.trim().toLowerCase();
        UserAccount u = users.get(name);
        if (u == null) return false;
        String p = nvl(phone);
        if (!p.isEmpty()) {
            if (!p.matches("\\d{11}")) throw new IllegalArgumentException("手机号必须是 11 位数字");
            boolean taken = users.values().stream().anyMatch(a -> !a.username().equals(name) && p.equals(a.phone()));
            if (taken) throw new IllegalArgumentException("该手机号已被其他账号使用");
        }
        UserAccount next = new UserAccount(u.username(), u.passwordHash(), u.groups(), u.tenant(),
                u.displayName(), u.enabled(), nvl(gender), p, nvl(avatar));
        users.put(name, next);
        persist(next);
        log.info("账号 {} 个人资料已更新", name);
        return true;
    }

    public UserAccount findByPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String p = phone.trim();
        return users.values().stream().filter(a -> p.equals(a.phone())).findFirst().orElse(null);
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

    /**

     * 不可变账号值对象：绝不暴露密码哈希给外部（toString 里也不含）

     */
    public record UserAccount(String username, String passwordHash, List<String> groups,
                              String tenant, String displayName, boolean enabled,
                              String gender, String phone, String avatar) {
        public UserAccount(String username, String passwordHash, List<String> groups, String tenant, String displayName) {
            this(username, passwordHash, List.copyOf(groups), tenant, displayName, true, "", "", "");
        }
        public UserAccount(String username, String passwordHash, List<String> groups, String tenant,
                           String displayName, boolean enabled) {
            this(username, passwordHash, List.copyOf(groups), tenant, displayName, enabled, "", "", "");
        }
        @Override
        public String toString() {
            return "UserAccount{username='" + username + "', tenant='" + tenant + "', groups=" + groups + ", enabled=" + enabled + '}';
        }
    }
}
