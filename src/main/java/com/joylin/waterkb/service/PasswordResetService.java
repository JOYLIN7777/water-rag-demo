package com.joylin.waterkb.service;

import com.joylin.waterkb.mapper.PasswordResetMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.joylin.waterkb.mapper.UserStore;
import com.joylin.waterkb.security.PasswordHasher;

/**
 * 忘记密码：用户提交申请 → 管理员核身批准并发放一次性重置码 → 用户凭码自助设置新密码。
 *
 *
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /**
     * 重置码字符集：去掉 I/O/0/1 这类肉眼易混字符，管理员口述、用户手抄不易出错
     */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    /**
     * 有效期：够用户找管理员要码并回来输入，又不至于长期挂着
     */
    private static final int TTL_MINUTES = 30;
    /**
     * 连续错误上限：防止拿一个已批准的单子暴力猜码
     */
    private static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetMapper mapper;
    private final UserStore users;
    private final PasswordHasher hasher;

    /**
     * 兼容单测：不接数据库。
     */
    public PasswordResetService(UserStore users, PasswordHasher hasher) {
        this(users, hasher, null);
    }

    @Autowired
    public PasswordResetService(UserStore users, PasswordHasher hasher, PasswordResetMapper mapper) {
        this.users = users;
        this.hasher = hasher;
        this.mapper = mapper;
    }


    /**
     * 提交重置申请。
     *
     */
    public void request(String username, String ip) {
        String name = normalize(username);
        if (name.isBlank()) return;
        if (users.find(name) == null) {
            log.info("密码重置申请：账号 {} 不存在，按统一话术受理", name);
            return;
        }
        try {
            int updated = mapper.touchPending(ip, name);
            if (updated == 0) {
                mapper.insert(name, ip);
            }
            log.info("账号 {} 提交了密码重置申请（来源 {}）", name, ip);
        } catch (Exception e) {
            log.warn("密码重置申请落库失败：{}", e.getMessage());
        }
    }

    /**
     * 后台待办列表：待审批 + 已发码未使用（后者让管理员能回答"码给了怎么还没用"）
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (Map<String, Object> row : mapper.listAll()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ((Number) row.get("id")).longValue());
                m.put("username", row.get("username"));
                m.put("status", row.get("status"));
                m.put("attempts", row.get("attempts"));
                m.put("requestIp", row.get("requestIp"));
                m.put("requestedAt", toMillis((Timestamp) row.get("requestedAt")));
                m.put("approvedBy", row.get("approvedBy"));
                m.put("approvedAt", toMillis((Timestamp) row.get("approvedAt")));
                m.put("expiresAt", toMillis((Timestamp) row.get("expiresAt")));
                m.put("usedAt", toMillis((Timestamp) row.get("usedAt")));
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("读取密码重置申请失败：{}", e.getMessage());
        }
        return out;
    }

    /**
     * 批准申请并生成一次性重置码，明文只在这一刻返回。
     * 已处理过的申请不允许重复批准，避免一单多发。
     */
    public String approve(long id, String admin) {
        String status = queryStatus(id);
        if (status == null) throw new IllegalArgumentException("重置申请不存在，可能已被清理");
        if (!"PENDING".equals(status)) throw new IllegalArgumentException("该申请已处理过，不能重复发放重置码");
        String code = randomCode();
        int n = mapper.approve(id, hasher.hash(code), admin,
                Timestamp.valueOf(LocalDateTime.now().plusMinutes(TTL_MINUTES)));
        if (n == 0) throw new IllegalArgumentException("该申请状态已变化，请刷新后重试");
        log.info("管理员 {} 为申请 #{} 发放了密码重置码", admin, id);
        return code;
    }

    public void reject(long id, String admin) {
        int n = mapper.reject(id, admin);
        if (n == 0) throw new IllegalArgumentException("该申请已处理过或不存在");
        log.info("管理员 {} 驳回了密码重置申请 #{}", admin, id);
    }

    /**
     * 用户凭重置码设置新密码。
     *
     * @return {@code null} 表示成功；否则是给用户直接看的错误文案
     */
    public String confirm(String username, String code, String newPassword) {
        String name = normalize(username);
        if (name.isBlank() || code == null || code.isBlank()) return "请填写账号与重置码";
        if (newPassword == null || newPassword.length() < 8) return "新密码至少 8 位";
        if (users.find(name) == null) return "账号不存在，或重置码已失效";
        String input = code.trim().toUpperCase();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (Map<String, Object> row : mapper.findValidCodes(name)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", ((Number) row.get("id")).longValue());
                m.put("codeHash", row.get("codeHash"));
                m.put("attempts", row.get("attempts"));
                rows.add(m);
            }
        } catch (Exception e) {
            log.warn("校验重置码失败：{}", e.getMessage());
            return "重置服务暂时不可用，请稍后重试";
        }
        if (rows.isEmpty()) return "重置码无效或已过期，请重新提交申请，由管理员重新发放";
        for (Map<String, Object> row : rows) {
            Object hash = row.get("codeHash");
            if (hash != null && hasher.matches(input, String.valueOf(hash))) {
                if (!users.resetPassword(name, newPassword)) return "新密码不符合要求或账号不可用";
                mapper.markUsed(((Number) row.get("id")).longValue());
                log.info("账号 {} 通过重置码完成了密码重置", name);
                return null;
            }
            // 记一次失败；连续错满上限就把单子作废，防暴力猜码
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            mapper.addAttempt(((Number) row.get("id")).longValue(), MAX_ATTEMPTS);
            if (attempts >= MAX_ATTEMPTS) {
                log.warn("账号 {} 的重置申请 #{} 因连续输错已作废", name, row.get("id"));
                return "重置码连续输错 " + MAX_ATTEMPTS + " 次，本次申请已作废，请重新提交申请";
            }
            return "重置码不正确，还可以再试 " + (MAX_ATTEMPTS - attempts) + " 次";
        }
        return "重置码不正确";
    }

    private String queryStatus(long id) {
        try {
            return mapper.queryStatus(id);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    private static Long toMillis(Timestamp ts) {
        return ts == null ? null : ts.getTime();
    }

    /**
     * 生成重置码；用 SecureRandom 而非 Random，避免可预测序列
     */
    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
