package com.joylin.waterkb.search;

import com.joylin.waterkb.security.OrgStore;
import com.joylin.waterkb.security.UserContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 部门知识库可见范围：整个系统的唯一过滤规则，检索、文档清单、分片预览共用。
 *
 * <p>模型（2026-11 廖工定稿）——文档归属上传者的部门（tenantId=org_key）：</p>
 * <ul>
 *   <li><b>向上永远可见</b>：子部门上传的文档，所有上级部门默认能看到（上级看下级是管理常态）；</li>
 *   <li><b>向下要主动下发</b>：上传时勾选「含下级部门」（visibleSubtree=true）后，
 *       该文档对本部门整个子树可见；不勾则只有本部门与上级能看；</li>
 *   <li><b>兄弟部门互不可见</b>：部门 A 看不到部门 B 的任何文档，隔离是硬边界；</li>
 *   <li><b>自己上传的永远可见</b>：账号调到别的部门后，自己传过的资料不丢；</li>
 *   <li><b>admin 不受限</b>：管理后台要能运维全部数据，问答端同样全可见；</li>
 *   <li><b>组角色不参与数据可见</b>：analyst/admin 这些是功能角色（决定能上传、能进后台），
 *       不是数据可见组——真库实测发现兄弟部门的同角色账号会通过组命中越权读到对方文档，
 *       所以数据可见性完全由部门规则 + owner 决定，allowedGroups 仅保留在元数据里不消费。</li>
 * </ul>
 *
 * <p>为什么抽成独立组件：检索（EsHybridSearchService）与清单/删除（EsIndexService）
 * 原来各自维护一份 filter 代码，改动规则要同步两处，漏一处就是越权或误删。
 * 现在规则只写一份，两个入口都从这里拿。</p>
 */
@Service
public class DocVisibilityService {

    private final OrgStore orgStore;

    public DocVisibilityService(OrgStore orgStore) {
        this.orgStore = orgStore;
    }

    /**
     * 当前用户的可见范围 ES query 对象（可直接放进 bool.filter / bool.must）。
     * admin 返回 match_all，即不加任何限制。
     */
    public String visibilityFilter() {
        UserContext.CurrentUser user = UserContext.get();
        if (user.hasGroup("admin")) {
            return "{\"match_all\":{}}";
        }
        String org = user.tenantId() == null || user.tenantId().isBlank()
                ? OrgStore.DEFAULT_KEY : user.tenantId();
        List<String> should = new ArrayList<>();
        // 1) 本部门的文档；default 租户兼容历史无 tenantId 的老数据
        should.add(orgClause(org));
        // 2) 祖先部门勾选了「含下级部门」下发的文档（只认下发，不是整棵祖先树全看）
        List<String> ancestors = orgStore.ancestorKeys(org);
        if (!ancestors.isEmpty()) {
            should.add("{\"bool\":{\"must\":[{\"terms\":{\"tenantId\":[" + joinQuote(ancestors) + "]}},"
                    + "{\"term\":{\"visibleSubtree\":true}}]}}");
        }
        // 3) 全部下级部门的文档（上级默认可见，无需文档做任何标记）
        Set<String> subtree = new LinkedHashSet<>(orgStore.subtreeKeys(org));
        subtree.remove(org);
        if (!subtree.isEmpty()) {
            should.add("{\"terms\":{\"tenantId\":[" + joinQuote(subtree) + "]}}");
        }
        // 4) 自己上传的（换部门后依然可见）
        // 注意：allowedGroups 不参与过滤——功能角色组（analyst 等）若命中会让兄弟部门
        // 的同角色账号越权读到对方文档，数据可见边界只认部门规则 + owner
        should.add("{\"term\":{\"ownerUser\":" + quote(user.userId()) + "}}");
        return "{\"bool\":{\"should\":[" + String.join(",", should) + "],\"minimum_should_match\":1}}";
    }

    /**
     * 删除范围：非 admin 只能删<b>本部门</b>的文档——祖先下发的、下级部门的同名文档
     * 虽然可见，但归属不在自己手里，不能删（否则父部门下发的资料会被子部门误删光）。
     */
    public String deleteScopeFilter() {
        UserContext.CurrentUser user = UserContext.get();
        if (user.hasGroup("admin")) {
            return "{\"match_all\":{}}";
        }
        String org = user.tenantId() == null || user.tenantId().isBlank()
                ? OrgStore.DEFAULT_KEY : user.tenantId();
        return "{\"term\":{\"tenantId\":" + quote(org) + "}}";
    }

    /** 本部门子句：default 兼容历史无 tenantId 的老数据 */
    private String orgClause(String org) {
        if (OrgStore.DEFAULT_KEY.equals(org)) {
            return "{\"bool\":{\"should\":[{\"term\":{\"tenantId\":\"default\"}},"
                    + "{\"bool\":{\"must_not\":{\"exists\":{\"field\":\"tenantId\"}}}}],"
                    + "\"minimum_should_match\":1}}";
        }
        return "{\"term\":{\"tenantId\":" + quote(org) + "}}";
    }

    private static String joinQuote(java.util.Collection<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(quote(v));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
