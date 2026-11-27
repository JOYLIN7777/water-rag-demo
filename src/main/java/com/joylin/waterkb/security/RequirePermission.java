package com.joylin.waterkb.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明访问接口所需的权限码，由 {@link PermissionInterceptor} 在方法执行前强制校验。
 *
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 需要的权限码，满足其中任意一个即可（除非 all=true）
     */
    String[] value();

    /**
     * 是否要求全部权限都满足
     */
    boolean all() default false;
}
