package com.joylin.waterkb.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 密码重置申请数据访问。
 */
@Mapper
public interface PasswordResetMapper {

    /**
     * 刷新待审批申请时间。
     */
    int touchPending(@Param("ip") String ip, @Param("username") String username);

    /**
     * 新建申请。
     */
    int insert(@Param("username") String username, @Param("ip") String ip);

    /**
     * 查询全部申请。
     */
    List<Map<String, Object>> listAll();

    /**
     * 批准申请。
     */
    int approve(@Param("id") long id, @Param("codeHash") String codeHash,
                @Param("admin") String admin, @Param("expiresAt") Timestamp expiresAt);

    /**
     * 驳回申请。
     */
    int reject(@Param("id") long id, @Param("admin") String admin);

    /**
     * 查询有效重置码。
     */
    List<Map<String, Object>> findValidCodes(@Param("username") String username);

    /**
     * 标记为已使用。
     */
    int markUsed(@Param("id") long id);

    /**
     * 累计失败次数。
     */
    int addAttempt(@Param("id") long id, @Param("maxAttempts") int maxAttempts);

    /**
     * 查询申请状态。
     */
    String queryStatus(@Param("id") long id);
}
