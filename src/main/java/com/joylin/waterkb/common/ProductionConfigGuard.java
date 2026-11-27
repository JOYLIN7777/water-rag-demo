package com.joylin.waterkb.common;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产配置守卫。
 *
 */
@Component
public class ProductionConfigGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);
    private static final String DEMO_JWT_SECRET = "water-rag-demo-dev-secret-change-in-prod";
    private static final String DEMO_DB_PASSWORD = "rag123";
    private static final String DEMO_AUTH_USERS = "Admin@123456";

    private final Environment env;
    private final String jwtSecret;
    private final String dbPassword;
    private final String authUsers;
    private final boolean requireLogin;

    /**
     * 构造配置守卫。
     */
    public ProductionConfigGuard(Environment env,
                                 @Value("${security.jwt.secret:}") String jwtSecret,
                                 @Value("${spring.datasource.password:}") String dbPassword,
                                 @Value("${security.auth.users:}") String authUsers,
                                 @Value("${security.auth.require-login:true}") boolean requireLogin) {
        this.env = env;
        this.jwtSecret = jwtSecret;
        this.dbPassword = dbPassword;
        this.authUsers = authUsers;
        this.requireLogin = requireLogin;
    }

    /**
     * 启动时校验生产配置。
     */
    @PostConstruct
    public void check() {
        boolean prod = env.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"));
        if (!prod) {
            log.warn("非生产环境：允许使用默认演示凭据与降级开关");
            return;
        }
        StringBuilder errors = new StringBuilder();
        if (jwtSecret == null || jwtSecret.isBlank() || DEMO_JWT_SECRET.equals(jwtSecret)) {
            errors.append("JWT_SECRET必须显式配置且不能使用演示值; ");
        }
        if (dbPassword == null || dbPassword.isBlank() || DEMO_DB_PASSWORD.equals(dbPassword)) {
            errors.append("DB_PASSWORD必须显式配置且不能使用演示值; ");
        }
        if (authUsers == null || authUsers.isBlank() || authUsers.contains(DEMO_AUTH_USERS)) {
            errors.append("AUTH_USERS必须显式配置且不能包含演示密码; ");
        }
        if (!requireLogin) {
            errors.append("生产环境禁止REQUIRE_LOGIN=false（X-User头降级可绕过认证）; ");
        }
        if (errors.length() > 0) {
            throw new IllegalStateException("生产配置校验失败：" + errors);
        }
    }
}
