package com.joylin.waterkb.mapper;

import com.joylin.waterkb.model.ModelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 模型配置 Mapper（MyBatis）：model_config 表的 CRUD。
 * DDL 与种子初始化仍由 ModelConfigRepository 负责，这里只做数据访问。
 */
@Mapper
public interface ModelConfigMapper {

    List<ModelConfig> findAll();

    ModelConfig findById(@Param("id") long id);

    /**

     * 激活的模型，最后激活的优先

     */
    List<ModelConfig> findActive(@Param("type") String type);

    /**

     * 全局默认优先，其次最后激活的

     */
    List<ModelConfig> findDefault(@Param("type") String type);

    int clearDefault(@Param("type") String type);

    int setDefault(@Param("id") long id);

    int clearActiveByType(@Param("type") String type);

    int activate(@Param("id") long id);

    int deactivate(@Param("id") long id);

    int insert(@Param("name") String name, @Param("type") String type, @Param("baseUrl") String baseUrl,
               @Param("apiKey") String apiKey, @Param("model") String model,
               @Param("dimensions") Integer dimensions, @Param("active") boolean active);

    int update(@Param("id") long id, @Param("name") String name, @Param("type") String type,
               @Param("baseUrl") String baseUrl, @Param("apiKey") String apiKey,
               @Param("model") String model, @Param("dimensions") Integer dimensions,
               @Param("enabled") boolean enabled);

    int delete(@Param("id") long id);

    int count();
}
